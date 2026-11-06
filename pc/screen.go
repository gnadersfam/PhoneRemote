package main

// Screen view, sound and screenshots - the PC side.
//
// Nothing here runs until the phone asks. The screen is only looked at while
// a phone is actually watching, and it all stops the moment the phone closes
// the viewer.
//
// The phone must have paired (secret token) AND have typed the screen PIN
// shown in the PC window before it can watch or listen.

import (
	"bytes"
	"crypto/subtle"
	"encoding/binary"
	"hash/crc32"
	"image"
	"image/jpeg"
	"log"
	"net/http"
	"runtime"
	"strconv"
	"sync/atomic"
	"time"
)

const viewSessionLife = 2 * time.Hour

// ---------------------------------------------------------------- the screen PIN

func (a *app) viewOK(r *http.Request) bool {
	got := r.Header.Get("X-View")
	if got == "" {
		return false
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	exp, ok := a.views[got]
	if !ok {
		return false
	}
	if time.Now().After(exp) {
		delete(a.views, got)
		return false
	}
	a.views[got] = time.Now().Add(viewSessionLife)
	return true
}

// Paired phone + the screen PIN typed in.
func (a *app) viewAuthed(next http.HandlerFunc) http.HandlerFunc {
	return a.authed(func(w http.ResponseWriter, r *http.Request) {
		if !a.viewOK(r) {
			writeJSON(w, 403, map[string]any{"error": "pin"})
			return
		}
		next(w, r)
	})
}

func (a *app) handleScreenUnlock(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 1024)
	r.ParseForm()
	got := r.Form.Get("pin")

	a.mu.Lock()
	defer a.mu.Unlock()
	if time.Now().Before(a.pinLockUntil) {
		writeJSON(w, 429, map[string]any{"error": "wait"})
		return
	}
	pin := a.cfg.ScreenPin
	if pin != "" && len(got) == len(pin) && subtle.ConstantTimeCompare([]byte(got), []byte(pin)) == 1 {
		a.pinBad = 0
		now := time.Now()
		for k, exp := range a.views {
			if now.After(exp) {
				delete(a.views, k)
			}
		}
		tok := randomToken()
		a.views[tok] = now.Add(viewSessionLife)
		writeJSON(w, 200, map[string]any{"view": tok})
		return
	}
	a.pinBad++
	if a.pinBad >= 5 {
		a.pinBad = 0
		a.pinLockUntil = time.Now().Add(60 * time.Second)
		writeJSON(w, 429, map[string]any{"error": "wait"})
		return
	}
	writeJSON(w, 403, map[string]any{"error": "wrong pin"})
}

// A new PIN also signs out any phone that was already watching.
func (a *app) newScreenPin() {
	a.mu.Lock()
	a.cfg.ScreenPin = randomDigits()
	a.views = map[string]time.Time{}
	a.pinBad = 0
	a.pinLockUntil = time.Time{}
	a.save()
	a.mu.Unlock()
}

func (a *app) handleUIPinNew(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	a.newScreenPin()
	writeJSON(w, 200, map[string]any{"ok": true})
}

// ---------------------------------------------------------------- taking and shrinking pictures

// Shrinks the captured screen (blue-green-red, 4 bytes a pixel) into dst (red-green-blue-alpha).
func scaleBGRA(src []byte, sw, sh int, dst *image.RGBA) {
	dw, dh := dst.Rect.Dx(), dst.Rect.Dy()
	if dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0 || len(src) < sw*sh*4 {
		return
	}
	bx := sw / dw
	if bx < 1 {
		bx = 1
	}
	by := sh / dh
	if by < 1 {
		by = 1
	}
	stepX := bx / 3
	if stepX < 1 {
		stepX = 1
	}
	stepY := by / 3
	if stepY < 1 {
		stepY = 1
	}
	pix := dst.Pix
	for y := 0; y < dh; y++ {
		sy0 := y * sh / dh
		for x := 0; x < dw; x++ {
			sx0 := x * sw / dw
			var r, g, b, n int
			for yy := sy0; yy < sy0+by && yy < sh; yy += stepY {
				row := yy * sw * 4
				for xx := sx0; xx < sx0+bx && xx < sw; xx += stepX {
					i := row + xx*4
					b += int(src[i])
					g += int(src[i+1])
					r += int(src[i+2])
					n++
				}
			}
			o := y*dst.Stride + x*4
			if n == 0 {
				pix[o], pix[o+1], pix[o+2], pix[o+3] = 0, 0, 0, 255
				continue
			}
			pix[o] = uint8(r / n)
			pix[o+1] = uint8(g / n)
			pix[o+2] = uint8(b / n)
			pix[o+3] = 255
		}
	}
}

func frameSize(sw, sh, width int) (int, int) {
	dw := width
	if dw > sw {
		dw = sw
	}
	dh := sh * dw / sw
	if dh < 1 {
		dh = 1
	}
	return dw, dh
}

// Writes one message: 1 byte kind, 4 bytes length, then the bytes.
//
//	kind 1 = a JPEG picture of the screen, kind 0 = "still here" (no change).
func frameHeader(kind byte, n int) []byte {
	h := make([]byte, 5)
	h[0] = kind
	binary.BigEndian.PutUint32(h[1:], uint32(n))
	return h
}

// ---------------------------------------------------------------- one screenshot

func (a *app) handleScreenShot(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	width := clampInt(queryInt(r, "w", 1920), 320, 3840)
	quality := clampInt(queryInt(r, "q", 85), 30, 95)

	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	grab, closeGrab := osScreenOpen()
	if grab == nil {
		writeJSON(w, 500, map[string]any{"error": "no screen"})
		return
	}
	defer closeGrab()
	pix, sw, sh, ok := grab()
	if !ok {
		writeJSON(w, 500, map[string]any{"error": "could not read the screen"})
		return
	}
	dw, dh := frameSize(sw, sh, width)
	img := image.NewRGBA(image.Rect(0, 0, dw, dh))
	scaleBGRA(pix, sw, sh, img)
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: quality}); err != nil {
		writeJSON(w, 500, map[string]any{"error": "encode"})
		return
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Length", strconv.Itoa(buf.Len()))
	w.Write(buf.Bytes())
}

// ---------------------------------------------------------------- the live view

func (a *app) handleScreenStream(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	width := clampInt(queryInt(r, "w", 1100), 320, 2560)
	quality := clampInt(queryInt(r, "q", 55), 20, 95)
	fps := clampInt(queryInt(r, "fps", 10), 1, 20)

	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	grab, closeGrab := osScreenOpen()
	if grab == nil {
		writeJSON(w, 500, map[string]any{"error": "no screen"})
		return
	}
	defer closeGrab()

	atomic.AddInt32(&a.watching, 1)
	defer atomic.AddInt32(&a.watching, -1)
	log.Printf("screen view started (%dpx, quality %d, %d fps)", width, quality, fps)
	defer log.Printf("screen view stopped")

	rc := http.NewResponseController(w)
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(200)

	send := func(b []byte) bool {
		rc.SetWriteDeadline(time.Now().Add(10 * time.Second))
		if _, err := w.Write(b); err != nil {
			return false
		}
		return rc.Flush() == nil
	}

	tick := time.NewTicker(time.Second / time.Duration(fps))
	defer tick.Stop()

	var img *image.RGBA
	var buf bytes.Buffer
	var sum uint32
	haveSum := false
	lastSend := time.Now().Add(-time.Hour)
	ctx := r.Context()

	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
		pix, sw, sh, ok := grab()
		if !ok {
			// e.g. the PC is locked: say we are still here
			if time.Since(lastSend) > time.Second {
				if !send(frameHeader(0, 0)) {
					return
				}
				lastSend = time.Now()
			}
			continue
		}
		dw, dh := frameSize(sw, sh, width)
		if img == nil || img.Rect.Dx() != dw || img.Rect.Dy() != dh {
			img = image.NewRGBA(image.Rect(0, 0, dw, dh))
			haveSum = false
		}
		scaleBGRA(pix, sw, sh, img)
		s := crc32.ChecksumIEEE(img.Pix)
		if haveSum && s == sum {
			// nothing changed: send nothing, just say we are still here now and then
			if time.Since(lastSend) > time.Second {
				if !send(frameHeader(0, 0)) {
					return
				}
				lastSend = time.Now()
			}
			continue
		}
		sum, haveSum = s, true

		buf.Reset()
		buf.Write(frameHeader(1, 0))
		if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: quality}); err != nil {
			return
		}
		out := buf.Bytes()
		binary.BigEndian.PutUint32(out[1:5], uint32(len(out)-5))
		if !send(out) {
			return
		}
		lastSend = time.Now()
	}
}

// ---------------------------------------------------------------- the sound

// First 12 bytes: "PRA1", the sample rate (4 bytes), channels (2 bytes, always 2)
// and bits (2 bytes, always 16). After that: raw sound, 16-bit stereo.
func (a *app) handleAudioStream(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, map[string]any{"error": "method"})
		return
	}
	rc := http.NewResponseController(w)
	stop := make(chan struct{})
	go func() {
		<-r.Context().Done()
		close(stop)
	}()

	started := false
	send := func(b []byte) bool {
		rc.SetWriteDeadline(time.Now().Add(10 * time.Second))
		if _, err := w.Write(b); err != nil {
			return false
		}
		return rc.Flush() == nil
	}
	log.Printf("sound started")
	defer log.Printf("sound stopped")

	err := osAudioCapture(stop,
		func(rate int) bool {
			w.Header().Set("Content-Type", "application/octet-stream")
			w.Header().Set("Cache-Control", "no-store")
			w.WriteHeader(200)
			hdr := make([]byte, 12)
			copy(hdr, "PRA1")
			binary.LittleEndian.PutUint32(hdr[4:], uint32(rate))
			binary.LittleEndian.PutUint16(hdr[8:], 2)
			binary.LittleEndian.PutUint16(hdr[10:], 16)
			started = true
			return send(hdr)
		},
		func(pcm []byte) bool { return send(pcm) })
	if err != nil {
		log.Printf("sound: %v", err)
		if !started {
			writeJSON(w, 500, map[string]any{"error": "no sound"})
		}
	}
}
