//go:build windows

package main

// The Windows-only parts: volume, media keys, power, the tray icon.
// Only Go's own standard library is used, so there is nothing else to install.

import (
	"log"
	"math"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

func init() {
	// The tray icon's message loop must stay on the program's first thread.
	runtime.LockOSThread()
}

var (
	user32   = syscall.NewLazyDLL("user32.dll")
	kernel32 = syscall.NewLazyDLL("kernel32.dll")
	shell32  = syscall.NewLazyDLL("shell32.dll")
	ole32    = syscall.NewLazyDLL("ole32.dll")
	powrprof = syscall.NewLazyDLL("powrprof.dll")

	pKeybdEvent            = user32.NewProc("keybd_event")
	pEnumWindows           = user32.NewProc("EnumWindows")
	pIsWindowVisible       = user32.NewProc("IsWindowVisible")
	pGetWindowTextLength   = user32.NewProc("GetWindowTextLengthW")
	pGetWindowText         = user32.NewProc("GetWindowTextW")
	pGetWindowThreadPID    = user32.NewProc("GetWindowThreadProcessId")
	pRegisterClassEx       = user32.NewProc("RegisterClassExW")
	pCreateWindowEx        = user32.NewProc("CreateWindowExW")
	pDefWindowProc         = user32.NewProc("DefWindowProcW")
	pGetMessage            = user32.NewProc("GetMessageW")
	pTranslateMessage      = user32.NewProc("TranslateMessage")
	pDispatchMessage       = user32.NewProc("DispatchMessageW")
	pPostMessage           = user32.NewProc("PostMessageW")
	pLoadIcon              = user32.NewProc("LoadIconW")
	pCreatePopupMenu       = user32.NewProc("CreatePopupMenu")
	pAppendMenu            = user32.NewProc("AppendMenuW")
	pTrackPopupMenu        = user32.NewProc("TrackPopupMenu")
	pDestroyMenu           = user32.NewProc("DestroyMenu")
	pGetCursorPos          = user32.NewProc("GetCursorPos")
	pSetForegroundWindow   = user32.NewProc("SetForegroundWindow")
	pRegisterWindowMessage = user32.NewProc("RegisterWindowMessageW")

	pGetModuleHandle           = kernel32.NewProc("GetModuleHandleW")
	pCreateMutex               = kernel32.NewProc("CreateMutexW")
	pOpenProcess               = kernel32.NewProc("OpenProcess")
	pCloseHandle               = kernel32.NewProc("CloseHandle")
	pQueryFullProcessImageName = kernel32.NewProc("QueryFullProcessImageNameW")

	pShellNotifyIcon = shell32.NewProc("Shell_NotifyIconW")

	pCoInitializeEx   = ole32.NewProc("CoInitializeEx")
	pCoCreateInstance = ole32.NewProc("CoCreateInstance")

	pSetSuspendState = powrprof.NewProc("SetSuspendState")
)

func hidden(c *exec.Cmd) *exec.Cmd {
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	return c
}

func utf16(s string) *uint16 {
	p, _ := syscall.UTF16PtrFromString(s)
	return p
}

// ---------------------------------------------------------------- one copy only

func osAlreadyRunning() bool {
	_, _, err := pCreateMutex.Call(0, 0, uintptr(unsafe.Pointer(utf16("PhoneRemoteSingleInstance"))))
	if errno, ok := err.(syscall.Errno); ok && errno == 183 { // ERROR_ALREADY_EXISTS
		return true
	}
	return false
}

// ---------------------------------------------------------------- volume (Windows Core Audio)

type guid struct {
	d1 uint32
	d2 uint16
	d3 uint16
	d4 [8]byte
}

var (
	clsidMMDeviceEnumerator = guid{0xBCDE0395, 0xE52F, 0x467C, [8]byte{0x8E, 0x3D, 0xC4, 0x57, 0x92, 0x91, 0x69, 0x2E}}
	iidIMMDeviceEnumerator  = guid{0xA95664D2, 0x9614, 0x4F35, [8]byte{0xA7, 0x46, 0xDE, 0x8D, 0xB6, 0x36, 0x17, 0xE6}}
	iidIAudioEndpointVolume = guid{0x5CDF2C82, 0x841E, 0x4546, [8]byte{0x97, 0x22, 0x0C, 0xF7, 0x40, 0x78, 0x22, 0x9A}}
)

func comCall(obj uintptr, index uintptr, args ...uintptr) uintptr {
	vtbl := *(*uintptr)(unsafe.Pointer(obj))
	fn := *(*uintptr)(unsafe.Pointer(vtbl + index*unsafe.Sizeof(uintptr(0))))
	all := make([]uintptr, 0, len(args)+1)
	all = append(all, obj)
	all = append(all, args...)
	r, _, _ := syscall.SyscallN(fn, all...)
	return r
}

func hrFailed(hr uintptr) bool { return int32(hr) < 0 }

type audioReq struct {
	fn   func(vol uintptr)
	done chan struct{}
}

var (
	audioOnce sync.Once
	audioCh   = make(chan audioReq)
	audioMu   sync.Mutex
)

// Windows audio wants to be talked to from one steady thread.
func audioWorker() {
	runtime.LockOSThread()
	pCoInitializeEx.Call(0, 0)
	for req := range audioCh {
		func() {
			defer close(req.done)
			var enum, dev, vol uintptr
			hr, _, _ := pCoCreateInstance.Call(
				uintptr(unsafe.Pointer(&clsidMMDeviceEnumerator)), 0, 23,
				uintptr(unsafe.Pointer(&iidIMMDeviceEnumerator)),
				uintptr(unsafe.Pointer(&enum)))
			if hrFailed(hr) || enum == 0 {
				log.Printf("audio: could not start (%x)", hr)
				return
			}
			defer comCall(enum, 2)
			// IMMDeviceEnumerator::GetDefaultAudioEndpoint(eRender, eMultimedia)
			if hrFailed(comCall(enum, 4, 0, 1, uintptr(unsafe.Pointer(&dev)))) || dev == 0 {
				log.Printf("audio: no default speakers found")
				return
			}
			defer comCall(dev, 2)
			// IMMDevice::Activate(IAudioEndpointVolume)
			if hrFailed(comCall(dev, 3, uintptr(unsafe.Pointer(&iidIAudioEndpointVolume)), 23, 0, uintptr(unsafe.Pointer(&vol)))) || vol == 0 {
				log.Printf("audio: could not get the volume control")
				return
			}
			defer comCall(vol, 2)
			req.fn(vol)
		}()
	}
}

func audioDo(fn func(vol uintptr)) bool {
	audioMu.Lock()
	defer audioMu.Unlock()
	audioOnce.Do(func() { go audioWorker() })
	ran := false
	wrapped := func(vol uintptr) {
		ran = true
		fn(vol)
	}
	done := make(chan struct{})
	select {
	case audioCh <- audioReq{fn: wrapped, done: done}:
	case <-time.After(2 * time.Second):
		return false
	}
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		return false
	}
	return ran
}

func osVolumeGet() (int, bool, bool) {
	var level float32
	var mute int32
	ok := audioDo(func(vol uintptr) {
		comCall(vol, 9, uintptr(unsafe.Pointer(&level))) // GetMasterVolumeLevelScalar
		comCall(vol, 15, uintptr(unsafe.Pointer(&mute))) // GetMute
	})
	if !ok {
		return 0, false, false
	}
	return int(math.Round(float64(level) * 100)), mute != 0, true
}

func osVolumeSet(level int) bool {
	if level < 0 {
		level = 0
	}
	if level > 100 {
		level = 100
	}
	f := float32(level) / 100
	return audioDo(func(vol uintptr) {
		comCall(vol, 7, uintptr(math.Float32bits(f)), 0) // SetMasterVolumeLevelScalar
		if level > 0 {
			comCall(vol, 14, 0, 0) // SetMute(false)
		}
	})
}

func osMuteToggle() (bool, bool) {
	var muted bool
	ok := audioDo(func(vol uintptr) {
		var m int32
		comCall(vol, 15, uintptr(unsafe.Pointer(&m)))
		next := uintptr(0)
		if m == 0 {
			next = 1
		}
		comCall(vol, 14, next, 0)
		muted = next == 1
	})
	return muted, ok
}

// ---------------------------------------------------------------- media keys

func pressKey(vk uintptr) {
	pKeybdEvent.Call(vk, 0, 0, 0)
	pKeybdEvent.Call(vk, 0, 2, 0) // key up
}

func osMediaKey(action string) {
	switch action {
	case "playpause":
		pressKey(0xB3)
	case "next":
		pressKey(0xB0)
	case "prev":
		pressKey(0xB1)
	}
}

// ---------------------------------------------------------------- keyboard and mouse

var (
	pSendInput  = user32.NewProc("SendInput")
	pMouseEvent = user32.NewProc("mouse_event")
)

// One INPUT record for SendInput (40 bytes on 64-bit Windows), keyboard flavour.
type kbInput struct {
	typ   uint32
	_     uint32
	vk    uint16
	scan  uint16
	flags uint32
	time  uint32
	_     uint32
	extra uintptr
	_     [8]byte
}

func sendUnits(units []uint16) {
	for len(units) > 0 {
		n := len(units)
		if n > 100 {
			n = 100
		}
		inputs := make([]kbInput, 0, n*2)
		for _, u := range units[:n] {
			// KEYEVENTF_UNICODE (0x4), then the same again with KEYEVENTF_KEYUP (0x2)
			inputs = append(inputs, kbInput{typ: 1, scan: u, flags: 0x4}, kbInput{typ: 1, scan: u, flags: 0x4 | 0x2})
		}
		pSendInput.Call(uintptr(len(inputs)), uintptr(unsafe.Pointer(&inputs[0])), unsafe.Sizeof(inputs[0]))
		units = units[n:]
	}
}

func pressVK(vk uintptr, extended bool) {
	var f uintptr
	if extended {
		f = 1 // KEYEVENTF_EXTENDEDKEY
	}
	pKeybdEvent.Call(vk, 0, f, 0)
	pKeybdEvent.Call(vk, 0, f|2, 0)
}

var keyCodes = map[string]struct {
	vk  uintptr
	ext bool
}{
	"enter":     {0x0D, false},
	"backspace": {0x08, false},
	"tab":       {0x09, false},
	"esc":       {0x1B, false},
	"space":     {0x20, false},
	"delete":    {0x2E, true},
	"left":      {0x25, true},
	"up":        {0x26, true},
	"right":     {0x27, true},
	"down":      {0x28, true},
	"home":      {0x24, true},
	"end":       {0x23, true},
}

func osKey(name string, count int) {
	k, ok := keyCodes[name]
	if !ok {
		return
	}
	for i := 0; i < count; i++ {
		pressVK(k.vk, k.ext)
	}
}

func osTypeText(text string) {
	var pending []uint16
	flush := func() {
		if len(pending) > 0 {
			sendUnits(pending)
			pending = pending[:0]
		}
	}
	for _, r := range text {
		switch r {
		case '\n':
			flush()
			osKey("enter", 1)
		case '\r':
		case '\t':
			flush()
			osKey("tab", 1)
		default:
			if r > 0xFFFF {
				r1 := r - 0x10000
				pending = append(pending, uint16(0xD800+(r1>>10)), uint16(0xDC00+(r1&0x3FF)))
			} else {
				pending = append(pending, uint16(r))
			}
		}
	}
	flush()
}

func osMouseMove(dx, dy int) {
	pMouseEvent.Call(0x0001, uintptr(uint32(int32(dx))), uintptr(uint32(int32(dy))), 0, 0)
}

func osMouseClick(button string) {
	if button == "right" {
		pMouseEvent.Call(0x0008, 0, 0, 0, 0)
		pMouseEvent.Call(0x0010, 0, 0, 0, 0)
		return
	}
	pMouseEvent.Call(0x0002, 0, 0, 0, 0)
	pMouseEvent.Call(0x0004, 0, 0, 0, 0)
}

func osMouseScroll(dy int) {
	pMouseEvent.Call(0x0800, 0, 0, uintptr(uint32(int32(dy))), 0)
}

// ---------------------------------------------------------------- power

func osPower(action string) {
	switch action {
	case "sleep":
		pSetSuspendState.Call(0, 0, 0)
	case "restart":
		hidden(exec.Command("shutdown", "/r", "/t", "0")).Run()
	case "shutdown":
		hidden(exec.Command("shutdown", "/s", "/t", "0")).Run()
	}
}

// ---------------------------------------------------------------- what's playing
// Reads the title of the Spotify, YouTube (in a browser) or VLC window.

var (
	enumMu    sync.Mutex
	enumScore int
	enumBest  string
	enumCB    = syscall.NewCallback(enumProc)
)

func windowExe(hwnd uintptr) string {
	var pid uint32
	pGetWindowThreadPID.Call(hwnd, uintptr(unsafe.Pointer(&pid)))
	if pid == 0 {
		return ""
	}
	h, _, _ := pOpenProcess.Call(0x1000, 0, uintptr(pid)) // PROCESS_QUERY_LIMITED_INFORMATION
	if h == 0 {
		return ""
	}
	defer pCloseHandle.Call(h)
	buf := make([]uint16, 520)
	size := uint32(len(buf))
	r, _, _ := pQueryFullProcessImageName.Call(h, 0, uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&size)))
	if r == 0 {
		return ""
	}
	full := syscall.UTF16ToString(buf[:size])
	if i := strings.LastIndexAny(full, `\/`); i >= 0 {
		full = full[i+1:]
	}
	return strings.ToLower(full)
}

func nowFromWindow(exe, title string) (int, string) {
	switch exe {
	case "spotify.exe":
		if title == "Spotify" || title == "Spotify Free" || title == "Spotify Premium" || !strings.Contains(title, " - ") {
			return 0, ""
		}
		return 3, title
	case "chrome.exe", "msedge.exe", "firefox.exe", "brave.exe", "opera.exe", "vivaldi.exe":
		if i := strings.Index(title, " - YouTube"); i > 0 {
			t := title[:i]
			if strings.HasPrefix(t, "(") {
				if j := strings.Index(t, ") "); j > 0 && j < 6 {
					t = t[j+2:]
				}
			}
			return 2, t
		}
	case "vlc.exe":
		t := strings.TrimSuffix(title, " - VLC media player")
		if t != "" && t != "VLC media player" {
			return 1, t
		}
	}
	return 0, ""
}

func enumProc(hwnd, lparam uintptr) uintptr {
	vis, _, _ := pIsWindowVisible.Call(hwnd)
	if vis == 0 {
		return 1
	}
	n, _, _ := pGetWindowTextLength.Call(hwnd)
	if n == 0 || n > 400 {
		return 1
	}
	buf := make([]uint16, n+1)
	pGetWindowText.Call(hwnd, uintptr(unsafe.Pointer(&buf[0])), n+1)
	title := syscall.UTF16ToString(buf)
	exe := windowExe(hwnd)
	if exe == "" {
		return 1
	}
	score, text := nowFromWindow(exe, title)
	if score > enumScore {
		enumScore = score
		enumBest = text
	}
	return 1
}

func osNowPlaying() string {
	enumMu.Lock()
	defer enumMu.Unlock()
	enumScore = 0
	enumBest = ""
	pEnumWindows.Call(enumCB, 0)
	return enumBest
}

// ---------------------------------------------------------------- start with Windows

const runKey = `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`

func osAutostartGet() bool {
	return hidden(exec.Command("reg", "query", runKey, "/v", "PhoneRemote")).Run() == nil
}

func osAutostartSet(on bool) {
	if on {
		exe, err := os.Executable()
		if err != nil {
			return
		}
		hidden(exec.Command("reg", "add", runKey, "/v", "PhoneRemote", "/t", "REG_SZ", "/d", `"`+exe+`" -background`, "/f")).Run()
	} else {
		hidden(exec.Command("reg", "delete", runKey, "/v", "PhoneRemote", "/f")).Run()
	}
}

// ---------------------------------------------------------------- the window

func osOpenUI(url string) {
	edges := []string{
		`C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`,
		`C:\Program Files\Microsoft\Edge\Application\msedge.exe`,
	}
	for _, e := range edges {
		if _, err := os.Stat(e); err == nil {
			if exec.Command(e, "--app="+url, "--window-size=560,700").Start() == nil {
				return
			}
		}
	}
	hidden(exec.Command("rundll32", "url.dll,FileProtocolHandler", url)).Start()
}

// ---------------------------------------------------------------- the tray icon

type wndClassEx struct {
	size       uint32
	style      uint32
	wndProc    uintptr
	clsExtra   int32
	wndExtra   int32
	instance   uintptr
	icon       uintptr
	cursor     uintptr
	background uintptr
	menuName   *uint16
	className  *uint16
	iconSm     uintptr
}

type notifyIconData struct {
	size            uint32
	hwnd            uintptr
	id              uint32
	flags           uint32
	callbackMessage uint32
	icon            uintptr
	tip             [128]uint16
	state           uint32
	stateMask       uint32
	info            [256]uint16
	timeoutVersion  uint32
	infoTitle       [64]uint16
	infoFlags       uint32
	guidItem        guid
	balloonIcon     uintptr
}

type winMsg struct {
	hwnd    uintptr
	message uint32
	wParam  uintptr
	lParam  uintptr
	time    uint32
	ptX     int32
	ptY     int32
}

const trayMsg = 0x8000 + 1

var (
	trayHwnd       uintptr
	taskbarCreated uint32
	trayData       notifyIconData
	trayOpenFn     func()
	trayQuitFn     func()
)

func addTrayIcon(hwnd uintptr) {
	icon, _, _ := pLoadIcon.Call(0, 32512) // the standard application icon
	trayData = notifyIconData{}
	trayData.size = uint32(unsafe.Sizeof(trayData))
	trayData.hwnd = hwnd
	trayData.id = 1
	trayData.flags = 1 | 2 | 4 // message, icon, tip
	trayData.callbackMessage = trayMsg
	trayData.icon = icon
	tip, _ := syscall.UTF16FromString("Phone Remote")
	copy(trayData.tip[:], tip)
	pShellNotifyIcon.Call(0, uintptr(unsafe.Pointer(&trayData))) // NIM_ADD
}

func showTrayMenu(hwnd uintptr) {
	menu, _, _ := pCreatePopupMenu.Call()
	if menu == 0 {
		return
	}
	pAppendMenu.Call(menu, 0, 1, uintptr(unsafe.Pointer(utf16("Open window"))))
	pAppendMenu.Call(menu, 0, 2, uintptr(unsafe.Pointer(utf16("Quit"))))
	var pt struct{ x, y int32 }
	pGetCursorPos.Call(uintptr(unsafe.Pointer(&pt)))
	pSetForegroundWindow.Call(hwnd)
	cmd, _, _ := pTrackPopupMenu.Call(menu, 0x0100|0x0002, uintptr(pt.x), uintptr(pt.y), 0, hwnd, 0)
	pPostMessage.Call(hwnd, 0, 0, 0)
	pDestroyMenu.Call(menu)
	switch cmd {
	case 1:
		if trayOpenFn != nil {
			trayOpenFn()
		}
	case 2:
		if trayQuitFn != nil {
			trayQuitFn()
		}
	}
}

func trayWndProc(hwnd, msg, wparam, lparam uintptr) uintptr {
	if taskbarCreated != 0 && uint32(msg) == taskbarCreated {
		addTrayIcon(hwnd)
		return 0
	}
	if uint32(msg) == trayMsg {
		switch uint32(lparam) {
		case 0x202, 0x203: // left click, double click
			if trayOpenFn != nil {
				trayOpenFn()
			}
		case 0x205: // right click
			showTrayMenu(hwnd)
		}
		return 0
	}
	r, _, _ := pDefWindowProc.Call(hwnd, msg, wparam, lparam)
	return r
}

func osCleanup() {
	if trayHwnd != 0 {
		pShellNotifyIcon.Call(2, uintptr(unsafe.Pointer(&trayData))) // NIM_DELETE
	}
}

// Runs for as long as the program lives.
func osRunTray(openFn func(), quitFn func()) {
	trayOpenFn = openFn
	trayQuitFn = quitFn
	inst, _, _ := pGetModuleHandle.Call(0)
	className := utf16("PhoneRemoteTray")
	wc := wndClassEx{}
	wc.size = uint32(unsafe.Sizeof(wc))
	wc.wndProc = syscall.NewCallback(trayWndProc)
	wc.instance = inst
	wc.className = className
	pRegisterClassEx.Call(uintptr(unsafe.Pointer(&wc)))
	hwnd, _, _ := pCreateWindowEx.Call(0, uintptr(unsafe.Pointer(className)), uintptr(unsafe.Pointer(utf16("Phone Remote"))), 0, 0, 0, 0, 0, 0, 0, inst, 0)
	if hwnd == 0 {
		log.Printf("tray: could not make the hidden window")
		select {}
	}
	trayHwnd = hwnd
	tc, _, _ := pRegisterWindowMessage.Call(uintptr(unsafe.Pointer(utf16("TaskbarCreated"))))
	taskbarCreated = uint32(tc)
	addTrayIcon(hwnd)

	var m winMsg
	for {
		r, _, _ := pGetMessage.Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
		if int32(r) <= 0 {
			break
		}
		pTranslateMessage.Call(uintptr(unsafe.Pointer(&m)))
		pDispatchMessage.Call(uintptr(unsafe.Pointer(&m)))
	}
}
