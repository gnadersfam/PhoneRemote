//go:build !windows

package main

// Stand-ins so the program's logic can be tested away from Windows.
// Nothing here is used on a real PC.

import (
	"log"
	"sync"
)

var (
	fakeMu    sync.Mutex
	fakeVol   = 40
	fakeMuted = false
	fakeAuto  = false
)

func osAlreadyRunning() bool { return false }

func osVolumeGet() (int, bool, bool) {
	fakeMu.Lock()
	defer fakeMu.Unlock()
	return fakeVol, fakeMuted, true
}

func osVolumeSet(level int) bool {
	fakeMu.Lock()
	defer fakeMu.Unlock()
	fakeVol = level
	return true
}

func osMuteToggle() (bool, bool) {
	fakeMu.Lock()
	defer fakeMu.Unlock()
	fakeMuted = !fakeMuted
	return fakeMuted, true
}

func osTypeText(text string)       { log.Printf("fake type: %q", text) }
func osKey(name string, count int) { log.Printf("fake key: %s x%d", name, count) }
func osMouseMove(dx, dy int)       { log.Printf("fake mouse move: %d,%d", dx, dy) }
func osMouseClick(button string)   { log.Printf("fake mouse click: %s", button) }
func osMouseScroll(dy int)         { log.Printf("fake mouse scroll: %d", dy) }
func osMediaKey(action string)     { log.Printf("fake media key: %s", action) }
func osPower(action string)        { log.Printf("fake power: %s", action) }
func osNowPlaying() string         { return "Test Artist - Test Song" }
func osAutostartGet() bool         { fakeMu.Lock(); defer fakeMu.Unlock(); return fakeAuto }
func osAutostartSet(on bool)       { fakeMu.Lock(); fakeAuto = on; fakeMu.Unlock() }
func osOpenUI(url string)          { log.Printf("fake open window: %s", url) }
func osCleanup()                   {}
func osRunTray(openFn func(), quitFn func()) {
	select {}
}
