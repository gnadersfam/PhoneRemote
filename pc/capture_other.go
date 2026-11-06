//go:build !windows

package main

// Stand-ins so the screen view and sound can be tested away from Windows.
// They make a moving test picture and a steady tone. Nothing here is used on a real PC.

import (
	"log"
	"math"
	"time"
)

const fakeW, fakeH = 1280, 720

func osScreenOpen() (func() ([]byte, int, int, bool), func()) {
	pix := make([]byte, fakeW*fakeH*4)
	start := time.Now()
	grab := func() ([]byte, int, int, bool) {
		// a colour gradient with a white square that moves, so every picture differs
		t := int(time.Since(start) / (50 * time.Millisecond))
		sx := (t * 7) % (fakeW - 60)
		for y := 0; y < fakeH; y++ {
			for x := 0; x < fakeW; x++ {
				o := (y*fakeW + x) * 4
				pix[o] = byte(x * 255 / fakeW) // blue
				pix[o+1] = byte(y * 255 / fakeH)
				pix[o+2] = 90
				pix[o+3] = 0
				if x >= sx && x < sx+60 && y >= 300 && y < 360 {
					pix[o], pix[o+1], pix[o+2] = 255, 255, 255
				}
			}
		}
		return pix, fakeW, fakeH, true
	}
	return grab, func() {}
}

func osMouseSet(fx, fy float64) { log.Printf("fake mouse set: %.3f,%.3f", fx, fy) }
func osMouseButton(button string, down bool) {
	log.Printf("fake mouse button: %s down=%v", button, down)
}

func osAudioCapture(stop <-chan struct{}, onFormat func(rate int) bool, onData func(pcm []byte) bool) error {
	const rate = 48000
	if !onFormat(rate) {
		return nil
	}
	tick := time.NewTicker(20 * time.Millisecond)
	defer tick.Stop()
	phase := 0.0
	for {
		select {
		case <-stop:
			return nil
		case <-tick.C:
		}
		frames := rate / 50
		out := make([]byte, 0, frames*4)
		for i := 0; i < frames; i++ {
			v := int16(math.Sin(phase) * 8000)
			phase += 2 * math.Pi * 440 / rate
			out = append(out, byte(uint16(v)), byte(uint16(v)>>8), byte(uint16(v)), byte(uint16(v)>>8))
		}
		if !onData(out) {
			return nil
		}
	}
}
