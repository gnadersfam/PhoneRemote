//go:build windows

package main

// The Windows-only parts of the screen view: taking a picture of the screen,
// moving the pointer to a spot, and listening to what the PC is playing.
// Only Go's own standard library is used, so there is nothing else to install.

import (
	"encoding/binary"
	"fmt"
	"math"
	"runtime"
	"syscall"
	"time"
	"unsafe"
)

var (
	gdi32 = syscall.NewLazyDLL("gdi32.dll")

	pSetProcessDPIAware = user32.NewProc("SetProcessDPIAware")
	pGetSystemMetrics   = user32.NewProc("GetSystemMetrics")
	pGetDC              = user32.NewProc("GetDC")
	pReleaseDC          = user32.NewProc("ReleaseDC")
	pGetCursorInfo      = user32.NewProc("GetCursorInfo")
	pGetIconInfo        = user32.NewProc("GetIconInfo")
	pDrawIconEx         = user32.NewProc("DrawIconEx")
	pSetCursorPos       = user32.NewProc("SetCursorPos")

	pBitBlt             = gdi32.NewProc("BitBlt")
	pCreateCompatibleDC = gdi32.NewProc("CreateCompatibleDC")
	pCreateDIBSection   = gdi32.NewProc("CreateDIBSection")
	pSelectObject       = gdi32.NewProc("SelectObject")
	pDeleteObject       = gdi32.NewProc("DeleteObject")
	pDeleteDC           = gdi32.NewProc("DeleteDC")

	pCoUninitialize = ole32.NewProc("CoUninitialize")
	pCoTaskMemFree  = ole32.NewProc("CoTaskMemFree")

	iidIAudioClient        = guid{0x1CB9AD4C, 0xDBFA, 0x4C32, [8]byte{0xB1, 0x78, 0xC2, 0xF5, 0x68, 0xA7, 0x03, 0xB2}}
	iidIAudioCaptureClient = guid{0xC8ADBD64, 0xE71E, 0x48A0, [8]byte{0xA4, 0xDE, 0x18, 0x5C, 0x39, 0x5C, 0xD3, 0x17}}
)

func init() {
	// Without this, Windows tells us a scaled-down screen size on sharp displays.
	pSetProcessDPIAware.Call()
}

// ---------------------------------------------------------------- the screen

type bitmapInfoHeader struct {
	size          uint32
	width         int32
	height        int32
	planes        uint16
	bitCount      uint16
	compression   uint32
	sizeImage     uint32
	xPelsPerMeter int32
	yPelsPerMeter int32
	clrUsed       uint32
	clrImportant  uint32
}

type cursorInfo struct {
	size   uint32
	flags  uint32
	cursor uintptr
	x, y   int32
}

type iconInfo struct {
	isIcon uint32
	xHot   uint32
	yHot   uint32
	_      uint32
	mask   uintptr
	color  uintptr
}

// The main screen.
func screenSize() (int, int) {
	w, _, _ := pGetSystemMetrics.Call(0)
	h, _, _ := pGetSystemMetrics.Call(1)
	return int(w), int(h)
}

// The mouse pointer is not part of a screen copy, so it is drawn on afterwards.
func drawCursor(dc uintptr) {
	ci := cursorInfo{size: uint32(unsafe.Sizeof(cursorInfo{}))}
	r, _, _ := pGetCursorInfo.Call(uintptr(unsafe.Pointer(&ci)))
	if r == 0 || ci.flags&1 == 0 || ci.cursor == 0 { // 1 = the pointer is showing
		return
	}
	hx, hy := 0, 0
	var ii iconInfo
	if r2, _, _ := pGetIconInfo.Call(ci.cursor, uintptr(unsafe.Pointer(&ii))); r2 != 0 {
		hx, hy = int(ii.xHot), int(ii.yHot)
		if ii.mask != 0 {
			pDeleteObject.Call(ii.mask)
		}
		if ii.color != 0 {
			pDeleteObject.Call(ii.color)
		}
	}
	x := int32(int(ci.x) - hx)
	y := int32(int(ci.y) - hy)
	pDrawIconEx.Call(dc, uintptr(uint32(x)), uintptr(uint32(y)), ci.cursor, 0, 0, 0, 0, 3) // DI_NORMAL
}

// Returns a function that takes one picture of the screen each time it is called
// (4 bytes a pixel: blue, green, red, unused), and a function to tidy up.
// The picture is only good until the next call. Call both from the same thread.
func osScreenOpen() (func() ([]byte, int, int, bool), func()) {
	screenDC, _, _ := pGetDC.Call(0)
	if screenDC == 0 {
		return nil, nil
	}
	memDC, _, _ := pCreateCompatibleDC.Call(screenDC)
	if memDC == 0 {
		pReleaseDC.Call(0, screenDC)
		return nil, nil
	}

	var (
		bmp, oldObj uintptr
		bits        unsafe.Pointer
		curW, curH  int
	)
	freeBmp := func() {
		if bmp != 0 {
			pSelectObject.Call(memDC, oldObj)
			pDeleteObject.Call(bmp)
			bmp, oldObj, bits = 0, 0, nil
		}
	}

	grab := func() ([]byte, int, int, bool) {
		w, h := screenSize()
		if w <= 0 || h <= 0 {
			return nil, 0, 0, false
		}
		if bmp == 0 || w != curW || h != curH {
			freeBmp()
			bi := bitmapInfoHeader{
				size:     uint32(unsafe.Sizeof(bitmapInfoHeader{})),
				width:    int32(w),
				height:   -int32(h), // negative: the top row first
				planes:   1,
				bitCount: 32,
			}
			var p unsafe.Pointer
			hb, _, _ := pCreateDIBSection.Call(memDC, uintptr(unsafe.Pointer(&bi)), 0, uintptr(unsafe.Pointer(&p)), 0, 0)
			if hb == 0 || p == nil {
				return nil, 0, 0, false
			}
			bmp, bits, curW, curH = hb, p, w, h
			old, _, _ := pSelectObject.Call(memDC, bmp)
			oldObj = old
		}
		// SRCCOPY | CAPTUREBLT (CAPTUREBLT also copies see-through windows)
		r, _, _ := pBitBlt.Call(memDC, 0, 0, uintptr(w), uintptr(h), screenDC, 0, 0, 0x00CC0020|0x40000000)
		if r == 0 {
			return nil, 0, 0, false
		}
		drawCursor(memDC)
		return unsafe.Slice((*byte)(bits), w*h*4), w, h, true
	}

	closeFn := func() {
		freeBmp()
		pDeleteDC.Call(memDC)
		pReleaseDC.Call(0, screenDC)
	}
	return grab, closeFn
}

// ---------------------------------------------------------------- pointer and buttons

// Moves the pointer to a spot on the main screen. fx and fy run from 0 (left, top) to 1 (right, bottom).
func osMouseSet(fx, fy float64) {
	w, h := screenSize()
	if w <= 0 || h <= 0 {
		return
	}
	x := int(math.Round(fx * float64(w-1)))
	y := int(math.Round(fy * float64(h-1)))
	pSetCursorPos.Call(uintptr(uint32(int32(x))), uintptr(uint32(int32(y))))
}

func osMouseButton(button string, down bool) {
	var flag uintptr
	switch {
	case button == "right" && down:
		flag = 0x0008
	case button == "right":
		flag = 0x0010
	case down:
		flag = 0x0002
	default:
		flag = 0x0004
	}
	pMouseEvent.Call(flag, 0, 0, 0, 0)
}

// ---------------------------------------------------------------- the sound

func readSample(b []byte, bits int, isFloat bool) float32 {
	switch {
	case isFloat && bits == 32:
		return math.Float32frombits(binary.LittleEndian.Uint32(b))
	case bits == 16:
		return float32(int16(binary.LittleEndian.Uint16(b))) / 32768
	case bits == 24:
		v := int32(b[0])<<8 | int32(b[1])<<16 | int32(b[2])<<24
		return float32(v>>8) / 8388608
	case bits == 32:
		return float32(int32(binary.LittleEndian.Uint32(b))) / 2147483648
	}
	return 0
}

func appendS16(out []byte, x float32) []byte {
	if x > 1 {
		x = 1
	} else if x < -1 {
		x = -1
	}
	v := int16(x * 32767)
	return append(out, byte(uint16(v)), byte(uint16(v)>>8))
}

// Turns whatever the PC is playing into 16-bit stereo.
func appendFrames(out []byte, src []byte, frames, channels, block int, isFloat bool) []byte {
	per := block / channels
	bits := per * 8
	for f := 0; f < frames; f++ {
		fr := src[f*block : (f+1)*block]
		l := readSample(fr[0:], bits, isFloat)
		r := l
		if channels >= 2 {
			r = readSample(fr[per:], bits, isFloat)
		}
		if channels >= 3 { // centre channel into both sides
			c := readSample(fr[2*per:], bits, isFloat) * 0.707
			l += c
			r += c
		}
		if channels >= 6 { // side/back speakers into their own side
			l += readSample(fr[4*per:], bits, isFloat) * 0.5
			r += readSample(fr[5*per:], bits, isFloat) * 0.5
		}
		out = appendS16(out, l)
		out = appendS16(out, r)
	}
	return out
}

// Listens to what the default speakers are playing ("loopback") until stop is closed.
// onFormat is told the sample rate once; onData is given sound as it arrives.
// Either returning false ends it.
func osAudioCapture(stop <-chan struct{}, onFormat func(rate int) bool, onData func(pcm []byte) bool) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	pCoInitializeEx.Call(0, 0)
	defer pCoUninitialize.Call()

	var enum, dev, client, capture uintptr
	hr, _, _ := pCoCreateInstance.Call(
		uintptr(unsafe.Pointer(&clsidMMDeviceEnumerator)), 0, 23,
		uintptr(unsafe.Pointer(&iidIMMDeviceEnumerator)),
		uintptr(unsafe.Pointer(&enum)))
	if hrFailed(hr) || enum == 0 {
		return fmt.Errorf("could not start audio (%x)", uint32(hr))
	}
	defer comCall(enum, 2)
	if hrFailed(comCall(enum, 4, 0, 1, uintptr(unsafe.Pointer(&dev)))) || dev == 0 {
		return fmt.Errorf("no speakers found")
	}
	defer comCall(dev, 2)
	// IMMDevice::Activate(IAudioClient)
	if hrFailed(comCall(dev, 3, uintptr(unsafe.Pointer(&iidIAudioClient)), 23, 0, uintptr(unsafe.Pointer(&client)))) || client == 0 {
		return fmt.Errorf("could not open the speakers")
	}
	defer comCall(client, 2)

	// IAudioClient::GetMixFormat
	var pwfx unsafe.Pointer
	if hrFailed(comCall(client, 8, uintptr(unsafe.Pointer(&pwfx)))) || pwfx == nil {
		return fmt.Errorf("could not read the sound format")
	}
	defer pCoTaskMemFree.Call(uintptr(pwfx))
	wf := unsafe.Slice((*byte)(pwfx), 18)
	tag := binary.LittleEndian.Uint16(wf[0:])
	channels := int(binary.LittleEndian.Uint16(wf[2:]))
	rate := int(binary.LittleEndian.Uint32(wf[4:]))
	block := int(binary.LittleEndian.Uint16(wf[12:]))
	cb := binary.LittleEndian.Uint16(wf[16:])
	isFloat := tag == 3
	if tag == 0xFFFE && cb >= 22 {
		ext := unsafe.Slice((*byte)(pwfx), 40)
		isFloat = binary.LittleEndian.Uint32(ext[24:]) == 3 // the sub-format: 3 = float, 1 = whole numbers
	}
	if channels < 1 || block < channels || rate < 8000 {
		return fmt.Errorf("odd sound format (%d channels, %d Hz)", channels, rate)
	}

	// shared mode, loopback, 100 ms of buffer
	if hrFailed(comCall(client, 3, 0, 0x00020000, 1000000, 0, uintptr(pwfx), 0)) {
		return fmt.Errorf("could not start listening to the speakers")
	}
	// IAudioClient::GetService(IAudioCaptureClient)
	if hrFailed(comCall(client, 14, uintptr(unsafe.Pointer(&iidIAudioCaptureClient)), uintptr(unsafe.Pointer(&capture)))) || capture == 0 {
		return fmt.Errorf("could not get the sound reader")
	}
	defer comCall(capture, 2)
	if hrFailed(comCall(client, 10)) { // Start
		return fmt.Errorf("could not start the sound reader")
	}
	defer comCall(client, 11) // Stop

	if !onFormat(rate) {
		return nil
	}

	out := make([]byte, 0, rate/10*4)
	lastSent := time.Now()
	tick := time.NewTicker(10 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-stop:
			return nil
		case <-tick.C:
		}
		out = out[:0]
		for {
			var n uint32
			if hrFailed(comCall(capture, 5, uintptr(unsafe.Pointer(&n)))) { // GetNextPacketSize
				return fmt.Errorf("the speakers changed or went away")
			}
			if n == 0 {
				break
			}
			var data unsafe.Pointer
			var frames, flags uint32
			if hrFailed(comCall(capture, 3, uintptr(unsafe.Pointer(&data)), uintptr(unsafe.Pointer(&frames)), uintptr(unsafe.Pointer(&flags)), 0, 0)) {
				return fmt.Errorf("could not read the sound")
			}
			if frames > 0 {
				if flags&2 != 0 || data == nil { // 2 = silence
					out = append(out, make([]byte, int(frames)*4)...)
				} else {
					src := unsafe.Slice((*byte)(data), int(frames)*block)
					out = appendFrames(out, src, int(frames), channels, block, isFloat)
				}
			}
			comCall(capture, 4, uintptr(frames)) // ReleaseBuffer
		}
		if len(out) > 0 {
			if !onData(out) {
				return nil
			}
			lastSent = time.Now()
		} else if time.Since(lastSent) > time.Second {
			// Windows sends nothing while nothing is playing. A little silence now and
			// then tells the phone the connection is still alive.
			if !onData(make([]byte, rate/100*4)) {
				return nil
			}
			lastSent = time.Now()
		}
	}
}
