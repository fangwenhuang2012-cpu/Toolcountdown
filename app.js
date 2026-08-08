/**
 * Car Android Box Draggable Floating Popup Digital Countdown Engine
 * Optimized for Carlinkit Box TBox S2 & Android WebViews
 */

// Safe Storage Helper (In-memory fallback if localStorage throws)
const memoryStorage = {};
const safeStorage = {
    getItem: function(key, defaultVal = null) {
        try {
            const val = localStorage.getItem(key);
            return val !== null ? val : (memoryStorage[key] !== undefined ? memoryStorage[key] : defaultVal);
        } catch(e) {
            return memoryStorage[key] !== undefined ? memoryStorage[key] : defaultVal;
        }
    },
    setItem: function(key, val) {
        try { localStorage.setItem(key, val); } catch(e) {}
        memoryStorage[key] = String(val);
    }
};

// State variables
let countdownTime = 30;
let initialSetTime = 30;
let targetEndTime = 0;
let isRunning = false;
let inputBuffer = "030";
let audioEnabled = safeStorage.getItem('openclaw_countdown_audio') !== 'false';
let autoMinimizeOnComplete = safeStorage.getItem('openclaw_countdown_automin') !== 'false';

let audioCtx = null;
let tickerTimer = null;
let workerTimer = null;
let lastInputGuardTime = 0;

// Input Debounce Guard (100ms throttle, zero blocking)
function guardInput(e) {
    const now = Date.now();
    if (now - lastInputGuardTime < 100) return false;
    lastInputGuardTime = now;
    return true;
}

// Clean & Robust Web Audio Synthesizer
function playBeep(freq = 1200, duration = 0.1, type = 'sine', volume = 0.6) {
    if (!audioEnabled) return;
    try {
        const AudioCtxClass = window.AudioContext || window.webkitAudioContext;
        if (!audioCtx && AudioCtxClass) {
            audioCtx = new AudioCtxClass();
        }
        if (audioCtx) {
            if (audioCtx.state === 'suspended') {
                audioCtx.resume().catch(() => {});
            }
            const now = audioCtx.currentTime;
            const osc = audioCtx.createOscillator();
            const gain = audioCtx.createGain();
            
            osc.type = type;
            osc.frequency.setValueAtTime(freq, now);
            
            gain.gain.setValueAtTime(volume, now);
            gain.gain.linearRampToValueAtTime(0.001, now + duration);
            
            osc.connect(gain);
            gain.connect(audioCtx.destination);
            
            osc.start(now);
            osc.stop(now + duration);
        }
    } catch(e) {}
}

// Mốc 30s, 20s, 10s -> Kêu 2 tiếng liên tiếp!
function playDoubleTing() {
    playBeep(1200, 0.1, 'sine', 0.65);
    setTimeout(() => playBeep(1500, 0.12, 'sine', 0.7), 130);
}

// Từ 5s trở xuống (5, 4, 3, 2, 1) -> Mỗi giây kêu 1 tiếng!
function playSingleTick() {
    playBeep(1400, 0.09, 'sine', 0.7);
}

// Khi về 0s -> Chuông báo hoàn thành
function playFinishAlarm() {
    if (!audioEnabled) return;
    try {
        const notes = [880, 1108.73, 1318.51, 1760];
        notes.forEach((freq, idx) => {
            setTimeout(() => {
                playBeep(freq, 0.18, 'triangle', 0.7);
            }, idx * 110);
        });
    } catch(e) {}
}

// DOM elements
let floatingWidget, widgetHeader, floatingBubble, bubbleDigits, bubbleIcon;
let digit1, digit2, digit3, statusIndicator, statusIcon, statusText, widgetTitle;
let startBtn, pauseBtn, resetBtn, audioBtn, autoMinBtn;
let lastBeepedMilestone = -1;

function getElements() {
    floatingWidget = document.getElementById('floatingWidget');
    widgetHeader = document.getElementById('widgetHeader');
    floatingBubble = document.getElementById('floatingBubble');
    bubbleDigits = document.getElementById('bubbleDigits');
    bubbleIcon = document.getElementById('bubbleIcon');

    digit1 = document.getElementById('digit1');
    digit2 = document.getElementById('digit2');
    digit3 = document.getElementById('digit3');
    statusIndicator = document.getElementById('statusIndicator');
    statusIcon = document.getElementById('statusIcon');
    statusText = document.getElementById('statusText');
    widgetTitle = document.getElementById('widgetTitle');

    startBtn = document.getElementById('startBtn');
    pauseBtn = document.getElementById('pauseBtn');
    resetBtn = document.getElementById('resetBtn');
    audioBtn = document.getElementById('audioBtn');
    autoMinBtn = document.getElementById('autoMinBtn');
}

function updateDisplay() {
    const formatted = String(countdownTime).padStart(3, '0');
    const digits = formatted.slice(-3);

    if (digit1) digit1.textContent = digits[0];
    if (digit2) digit2.textContent = digits[1];
    if (digit3) digit3.textContent = digits[2];
    if (bubbleDigits) bubbleDigits.textContent = formatted;

    if (startBtn) startBtn.disabled = isRunning || countdownTime <= 0;
    if (pauseBtn) pauseBtn.disabled = !isRunning;

    renderPiPCanvas();
}

// Ticker Logic with Date.now() Real-World Time Tracking
function tick() {
    if (!isRunning) return;
    const now = Date.now();
    const calcRem = Math.max(0, Math.ceil((targetEndTime - now) / 1000));

    if (calcRem !== countdownTime) {
        const prevTime = countdownTime;
        countdownTime = calcRem;
        updateDisplay();

        // 30, 20, 10 seconds -> 2 consecutive beeps!
        if (prevTime >= 30 && countdownTime <= 30 && lastBeepedMilestone !== 30) {
            lastBeepedMilestone = 30;
            playDoubleTing();
        } else if (prevTime >= 20 && countdownTime <= 20 && lastBeepedMilestone !== 20) {
            lastBeepedMilestone = 20;
            playDoubleTing();
        } else if (prevTime >= 10 && countdownTime <= 10 && lastBeepedMilestone !== 10) {
            lastBeepedMilestone = 10;
            playDoubleTing();
        } else if (countdownTime <= 5 && countdownTime >= 1 && lastBeepedMilestone !== countdownTime) {
            lastBeepedMilestone = countdownTime;
            playSingleTick();
        }

        if (countdownTime <= 10 && countdownTime > 5) {
            if (floatingWidget) {
                floatingWidget.classList.add('warning-mode');
                floatingWidget.classList.remove('critical-mode');
            }
        } else if (countdownTime <= 5 && countdownTime > 0) {
            if (floatingWidget) {
                floatingWidget.classList.remove('warning-mode');
                floatingWidget.classList.add('critical-mode');
            }
        }
    }

    if (countdownTime <= 0) {
        stopTimer();
        playFinishAlarm();

        if (statusText) statusText.textContent = "HOÀN THÀNH";

        setTimeout(() => {
            if (document.pictureInPictureElement) {
                document.exitPictureInPicture().catch(() => {});
            }
            if (autoMinimizeOnComplete) {
                minimizeToBubble();
            } else {
                if (floatingWidget) floatingWidget.classList.add('hidden');
                if (floatingBubble) floatingBubble.classList.remove('hidden');
            }
        }, 1200);
    }
}

function startTickerEngine() {
    stopTickerEngine();
    tickerTimer = setInterval(tick, 150);

    try {
        const blob = new Blob([`
            let timer = null;
            self.onmessage = function(e) {
                if (e.data === 'start') {
                    clearInterval(timer);
                    timer = setInterval(function() { self.postMessage('tick'); }, 150);
                } else if (e.data === 'stop') {
                    clearInterval(timer);
                }
            };
        `], { type: 'application/javascript' });
        workerTimer = new Worker(URL.createObjectURL(blob));
        workerTimer.onmessage = function() { tick(); };
        workerTimer.postMessage('start');
    } catch(e) {}
}

function stopTickerEngine() {
    if (tickerTimer) clearInterval(tickerTimer);
    tickerTimer = null;
    if (workerTimer) {
        try { workerTimer.postMessage('stop'); workerTimer.terminate(); } catch(e) {}
        workerTimer = null;
    }
}

function startTimer() {
    if (countdownTime <= 0) return;

    initialSetTime = countdownTime;
    targetEndTime = Date.now() + (countdownTime * 1000);
    isRunning = true;

    if (statusText) statusText.textContent = "ĐANG ĐẾM";
    if (floatingWidget) floatingWidget.classList.add('running-mode');

    // Beep 2 times if starting at 30, 20, or 10
    if (countdownTime === 30) {
        lastBeepedMilestone = 30;
        playDoubleTing();
    } else if (countdownTime === 20) {
        lastBeepedMilestone = 20;
        playDoubleTing();
    } else if (countdownTime === 10) {
        lastBeepedMilestone = 10;
        playDoubleTing();
    } else if (countdownTime <= 5 && countdownTime >= 1) {
        lastBeepedMilestone = countdownTime;
        playSingleTick();
    } else {
        lastBeepedMilestone = -1;
    }

    updateDisplay();
    startTickerEngine();

    // Auto open PiP window on Start!
    if (!document.pictureInPictureElement && typeof togglePiP === 'function') {
        togglePiP();
    }
}

function pauseTimer() {
    if (isRunning) {
        stopTickerEngine();
        isRunning = false;
        if (statusText) statusText.textContent = "TẠM DỪNG";
        if (floatingWidget) {
            floatingWidget.classList.remove('running-mode', 'warning-mode', 'critical-mode');
        }
        updateDisplay();
    }
}

function stopTimer() {
    stopTickerEngine();
    isRunning = false;
    if (floatingWidget) {
        floatingWidget.classList.remove('running-mode', 'warning-mode', 'critical-mode');
    }
    updateDisplay();
}

function resetTimer() {
    stopTimer();
    countdownTime = initialSetTime || 30;
    inputBuffer = String(countdownTime).padStart(3, '0');
    if (statusText) statusText.textContent = "SẴN SÀNG";
    updateDisplay();
}

// Action Handlers bound directly to HTML onclick
function handlePreset(sec, e) {
    if (!guardInput(e)) return;
    stopTimer();
    countdownTime = sec;
    initialSetTime = sec;
    inputBuffer = String(sec).padStart(3, '0');
    safeStorage.setItem('openclaw_countdown_last_time', countdownTime);
    updateDisplay();
    startTimer();
}

function handleNum(val, e) {
    if (!guardInput(e)) return;
    if (isRunning) stopTimer();
    if (inputBuffer === "030" || inputBuffer === "000" || countdownTime === 0) {
        inputBuffer = String(val);
    } else if (inputBuffer.length >= 3) {
        inputBuffer = inputBuffer.slice(1) + String(val);
    } else {
        inputBuffer += String(val);
    }
    countdownTime = parseInt(inputBuffer, 10) || 0;
    countdownTime = Math.min(999, countdownTime);
    initialSetTime = countdownTime;
    safeStorage.setItem('openclaw_countdown_last_time', countdownTime);
    updateDisplay();
}

function handleClear(e) {
    if (!guardInput(e)) return;
    if (isRunning) stopTimer();
    inputBuffer = "000";
    countdownTime = 0;
    initialSetTime = 0;
    safeStorage.setItem('openclaw_countdown_last_time', 0);
    updateDisplay();
}

function handleBackspace(e) {
    if (!guardInput(e)) return;
    if (isRunning) return;
    if (inputBuffer.length > 0) {
        inputBuffer = inputBuffer.slice(0, -1);
    }
    countdownTime = parseInt(inputBuffer, 10) || 0;
    safeStorage.setItem('openclaw_countdown_last_time', countdownTime);
    updateDisplay();
}

function handleStart(e) {
    if (!guardInput(e)) return;
    startTimer();
}

function handlePause(e) {
    if (!guardInput(e)) return;
    pauseTimer();
}

function handleReset(e) {
    if (!guardInput(e)) return;
    resetTimer();
}

function handleToggleAudio(e) {
    if (!guardInput(e)) return;
    audioEnabled = !audioEnabled;
    if (audioBtn) audioBtn.textContent = audioEnabled ? '🔊' : '🔇';
    safeStorage.setItem('openclaw_countdown_audio', audioEnabled);
}

function handleToggleAutoMin(e) {
    if (!guardInput(e)) return;
    autoMinimizeOnComplete = !autoMinimizeOnComplete;
    if (autoMinBtn) autoMinBtn.textContent = autoMinimizeOnComplete ? '🎈 TỰ ẨN' : '📌 GIỮ NGUYÊN';
    safeStorage.setItem('openclaw_countdown_automin', autoMinimizeOnComplete);
}

// Size Presets & Resizing Logic (+75% Default Increase)
const SIZE_PRESETS = [
    { w: 735, h: 510, label: "+75%" },
    { w: 840, h: 580, label: "+100%" },
    { w: 560, h: 390, label: "+35%" },
    { w: 420, h: 290, label: "Gốc" }
];
let currentSizeIndex = 0;

function applyWidgetSize(w, h, save = true) {
    if (!floatingWidget) return;
    const maxW = Math.min(window.innerWidth - 10, window.innerWidth);
    const maxH = Math.min(window.innerHeight - 10, window.innerHeight);

    const targetW = Math.max(340, Math.min(maxW, w));
    const targetH = Math.max(220, Math.min(maxH, h));

    floatingWidget.style.width = targetW + 'px';
    floatingWidget.style.height = targetH + 'px';

    if (save) {
        safeStorage.setItem('openclaw_countdown_width', targetW);
        safeStorage.setItem('openclaw_countdown_height', targetH);
    }

    if (window.AndroidBridge && window.AndroidBridge.updateWindowBounds) {
        const curX = parseFloat(floatingWidget.dataset.x || 15);
        const curY = parseFloat(floatingWidget.dataset.y || 15);
        window.AndroidBridge.updateWindowBounds(curX, curY, Math.round(targetW), Math.round(targetH));
    }
}

function handleToggleSize(e) {
    if (!guardInput(e)) return;
    currentSizeIndex = (currentSizeIndex + 1) % SIZE_PRESETS.length;
    const preset = SIZE_PRESETS[currentSizeIndex];
    applyWidgetSize(preset.w, preset.h, true);
}

function showWidget(e) {
    if (e && e.stopPropagation) e.stopPropagation();
    if (document.pictureInPictureElement) {
        document.exitPictureInPicture().catch(() => {});
    }
    if (floatingWidget) floatingWidget.classList.remove('hidden');
    if (floatingBubble) floatingBubble.classList.add('hidden');
}

function minimizeToBubble(e) {
    if (e && e.stopPropagation) e.stopPropagation();
    if (floatingWidget) floatingWidget.classList.add('hidden');
    if (floatingBubble) floatingBubble.classList.remove('hidden');
}

// Drag and Drop Logic
function makeDraggable(element, handleElement) {
    if (!element) return;
    const target = handleElement || element;
    let currentX = 0, currentY = 0, initialX = 0, initialY = 0;
    let isDragging = false, startX = 0, startY = 0, totalMoved = 0;

    const getPos = (e) => {
        if (e.touches && e.touches.length > 0) return { x: e.touches[0].clientX, y: e.touches[0].clientY };
        if (e.changedTouches && e.changedTouches.length > 0) return { x: e.changedTouches[0].clientX, y: e.changedTouches[0].clientY };
        return { x: e.clientX || 0, y: e.clientY || 0 };
    };

    const dragStart = (e) => {
        if (e.target.closest('button, input, textarea, .resize-handle')) return;
        const pos = getPos(e);
        startX = pos.x; startY = pos.y;
        totalMoved = 0; isDragging = true;
        currentX = parseFloat(element.dataset.x || 0);
        currentY = parseFloat(element.dataset.y || 0);
        initialX = startX - currentX;
        initialY = startY - currentY;
        element.classList.add('is-dragging');

        const dragMove = (e) => {
            if (!isDragging) return;
            const p = getPos(e);
            totalMoved = Math.hypot(p.x - startX, p.y - startY);
            if (totalMoved > 5 && e.cancelable) e.preventDefault();
            currentX = p.x - initialX;
            currentY = p.y - initialY;
            element.style.transform = `translate3d(${currentX}px, ${currentY}px, 0)`;
            element.dataset.x = currentX;
            element.dataset.y = currentY;
        };

        const dragEnd = () => {
            if (!isDragging) return;
            isDragging = false;
            element.classList.remove('is-dragging');
            document.removeEventListener('mousemove', dragMove);
            document.removeEventListener('mouseup', dragEnd);
            document.removeEventListener('touchmove', dragMove);
            document.removeEventListener('touchend', dragEnd);
        };

        document.addEventListener('mousemove', dragMove);
        document.addEventListener('mouseup', dragEnd);
        document.addEventListener('touchmove', dragMove, { passive: false });
        document.addEventListener('touchend', dragEnd);
    };

    target.addEventListener('mousedown', dragStart);
    target.addEventListener('touchstart', dragStart, { passive: false });
}

// Corner Drag-to-Resize Logic
function makeResizable(element, handleElement) {
    if (!element || !handleElement) return;
    let startW = 0, startH = 0, startX = 0, startY = 0;
    let isResizing = false;

    const getPos = (e) => {
        if (e.touches && e.touches.length > 0) return { x: e.touches[0].clientX, y: e.touches[0].clientY };
        if (e.changedTouches && e.changedTouches.length > 0) return { x: e.changedTouches[0].clientX, y: e.changedTouches[0].clientY };
        return { x: e.clientX || 0, y: e.clientY || 0 };
    };

    const resizeStart = (e) => {
        if (e.stopPropagation) e.stopPropagation();
        if (e.preventDefault && e.cancelable) e.preventDefault();

        const pos = getPos(e);
        startX = pos.x;
        startY = pos.y;

        const rect = element.getBoundingClientRect();
        startW = rect.width;
        startH = rect.height;
        isResizing = true;
        element.classList.add('is-resizing');

        const resizeMove = (e) => {
            if (!isResizing) return;
            if (e.preventDefault && e.cancelable) e.preventDefault();

            const p = getPos(e);
            const dx = p.x - startX;
            const dy = p.y - startY;

            const maxW = Math.min(window.innerWidth - 10, window.innerWidth);
            const maxH = Math.min(window.innerHeight - 10, window.innerHeight);

            let newW = Math.max(340, Math.min(maxW, startW + dx));
            let newH = Math.max(220, Math.min(maxH, startH + dy));

            element.style.width = newW + 'px';
            element.style.height = newH + 'px';

            if (window.AndroidBridge && window.AndroidBridge.updateWindowBounds) {
                const curX = parseFloat(element.dataset.x || 15);
                const curY = parseFloat(element.dataset.y || 15);
                window.AndroidBridge.updateWindowBounds(curX, curY, Math.round(newW), Math.round(newH));
            }
        };

        const resizeEnd = () => {
            if (!isResizing) return;
            isResizing = false;
            element.classList.remove('is-resizing');

            const finalRect = element.getBoundingClientRect();
            safeStorage.setItem('openclaw_countdown_width', Math.round(finalRect.width));
            safeStorage.setItem('openclaw_countdown_height', Math.round(finalRect.height));

            document.removeEventListener('mousemove', resizeMove);
            document.removeEventListener('mouseup', resizeEnd);
            document.removeEventListener('touchmove', resizeMove);
            document.removeEventListener('touchend', resizeEnd);
        };

        document.addEventListener('mousemove', resizeMove);
        document.addEventListener('mouseup', resizeEnd);
        document.addEventListener('touchmove', resizeMove, { passive: false });
        document.addEventListener('touchend', resizeEnd);
    };

    handleElement.addEventListener('mousedown', resizeStart);
    handleElement.addEventListener('touchstart', resizeStart, { passive: false });

    // Double-click corner handle resets to default (+75%: 735x510)
    handleElement.addEventListener('dblclick', (e) => {
        if (e.stopPropagation) e.stopPropagation();
        applyWidgetSize(735, 510, true);
    });
}

// Init App
function init() {
    getElements();
    if (audioBtn) audioBtn.textContent = audioEnabled ? '🔊' : '🔇';
    if (autoMinBtn) autoMinBtn.textContent = autoMinimizeOnComplete ? '🎈 TỰ ẨN' : '📌 GIỮ NGUYÊN';
    
    const savedTime = safeStorage.getItem('openclaw_countdown_last_time');
    const parsed = parseInt(savedTime, 10);
    if (!isNaN(parsed) && parsed > 0) {
        countdownTime = parsed;
        initialSetTime = parsed;
        inputBuffer = String(parsed).padStart(3, '0');
    } else {
        countdownTime = 30;
        initialSetTime = 30;
        inputBuffer = "030";
    }

    if (statusText) statusText.textContent = "SẴN SÀNG";
    updateDisplay();

    // Restore saved widget size or default to 735x510 (+75%)
    const savedW = parseInt(safeStorage.getItem('openclaw_countdown_width'), 10);
    const savedH = parseInt(safeStorage.getItem('openclaw_countdown_height'), 10);
    if (!isNaN(savedW) && !isNaN(savedH) && savedW >= 340 && savedH >= 220) {
        applyWidgetSize(savedW, savedH, false);
    } else {
        applyWidgetSize(735, 510, false);
    }

    makeDraggable(floatingWidget, widgetHeader);
    makeDraggable(floatingBubble, null);

    const resizeHandle = document.getElementById('resizeHandle');
    if (resizeHandle) makeResizable(floatingWidget, resizeHandle);

    initPiPElements();
}

// ==========================================
// PICTURE-IN-PICTURE (PiP) CANVAS ENGINE
// Zero permissions required on Android Box!
// ==========================================
let pipCanvas = null;
let pipCtx = null;
let pipVideo = null;
let isPiPActive = false;
let pipBtn = null;

function initPiPElements() {
    pipBtn = document.getElementById('pipBtn');
    if (!pipCanvas) {
        pipCanvas = document.createElement('canvas');
        pipCanvas.width = 480;
        pipCanvas.height = 270;
        pipCanvas.style.display = 'none';
        document.body.appendChild(pipCanvas);
        pipCtx = pipCanvas.getContext('2d');
    }
    if (!pipVideo) {
        pipVideo = document.createElement('video');
        pipVideo.autoplay = true;
        pipVideo.muted = true;
        pipVideo.playsInline = true;
        pipVideo.style.display = 'none';
        document.body.appendChild(pipVideo);

        pipVideo.addEventListener('enterpictureinpicture', () => {
            isPiPActive = true;
            updatePiPButtonState();
            if (floatingWidget) floatingWidget.classList.add('hidden');
            if (floatingBubble) floatingBubble.classList.add('hidden');
        });
        pipVideo.addEventListener('leavepictureinpicture', () => {
            isPiPActive = false;
            updatePiPButtonState();
            if (countdownTime <= 0) {
                if (floatingWidget) floatingWidget.classList.add('hidden');
                if (floatingBubble) floatingBubble.classList.remove('hidden');
            } else {
                if (floatingWidget) floatingWidget.classList.remove('hidden');
                if (floatingBubble) floatingBubble.classList.add('hidden');
            }
        });
    }
    renderPiPCanvas();
}

function updatePiPButtonState() {
    pipBtn = document.getElementById('pipBtn');
    if (!pipBtn) return;
    if (isPiPActive) {
        pipBtn.textContent = '❌ TẮT PiP';
        pipBtn.style.background = 'rgba(255, 0, 85, 0.35)';
        pipBtn.style.borderColor = 'var(--danger)';
        pipBtn.style.color = '#ff4d8d';
    } else {
        pipBtn.textContent = '📺 CỬA SỔ NỔI';
        pipBtn.style.background = 'rgba(0, 243, 255, 0.22)';
        pipBtn.style.borderColor = 'var(--primary)';
        pipBtn.style.color = 'var(--primary)';
    }
}

function renderPiPCanvas() {
    if (!pipCtx) return;
    const w = pipCanvas.width;
    const h = pipCanvas.height;

    // Determine Status & Color Palette
    let strokeColor = '#00f3ff';
    let glowColor = 'rgba(0, 243, 255, 0.6)';
    let currentStatus = statusText ? statusText.textContent : 'SẴN SÀNG';

    if (countdownTime <= 0) {
        strokeColor = '#00ff88';
        glowColor = 'rgba(0, 255, 136, 0.7)';
        currentStatus = 'HOÀN THÀNH 🎉';
    } else if (countdownTime <= 5 && isRunning) {
        strokeColor = '#ff0055';
        glowColor = 'rgba(255, 0, 85, 0.85)';
        currentStatus = 'CẢNH BÁO! ⚠️';
    } else if (countdownTime <= 10 && isRunning) {
        strokeColor = '#ffaa00';
        glowColor = 'rgba(255, 170, 0, 0.75)';
        currentStatus = 'SẮP HẾT GIỜ ⏱️';
    } else if (isRunning) {
        currentStatus = 'ĐANG ĐẾM... ⏱️';
    }

    // 1. Dark Glass Background
    pipCtx.fillStyle = '#060a12';
    pipCtx.fillRect(0, 0, w, h);

    const grad = pipCtx.createLinearGradient(0, 0, w, h);
    grad.addColorStop(0, '#0f172a');
    grad.addColorStop(1, '#040711');
    pipCtx.fillStyle = grad;
    pipCtx.fillRect(6, 6, w - 12, h - 12);

    // 2. Neon Glowing Border
    pipCtx.lineWidth = 6;
    pipCtx.strokeStyle = strokeColor;
    pipCtx.shadowColor = glowColor;
    pipCtx.shadowBlur = 18;
    pipCtx.strokeRect(10, 10, w - 20, h - 20);

    pipCtx.shadowBlur = 0;

    // 3. Status Bar Header
    pipCtx.font = 'bold 22px Inter, sans-serif';
    pipCtx.fillStyle = strokeColor;
    pipCtx.textAlign = 'center';
    pipCtx.textBaseline = 'top';
    pipCtx.fillText(currentStatus, w / 2, 22);

    // 4. Main Giant Digital Digits
    const formatted = String(countdownTime).padStart(3, '0');
    pipCtx.font = '900 110px Orbitron, "Courier New", monospace';
    pipCtx.fillStyle = strokeColor;
    pipCtx.shadowColor = glowColor;
    pipCtx.shadowBlur = 24;
    pipCtx.textAlign = 'center';
    pipCtx.textBaseline = 'middle';
    pipCtx.fillText(formatted, w / 2, 132);

    pipCtx.shadowBlur = 0;

    // 5. Unit Label
    pipCtx.font = 'bold 18px Inter, sans-serif';
    pipCtx.fillStyle = '#94a3b8';
    pipCtx.textBaseline = 'bottom';
    pipCtx.fillText('GIÂY', w / 2, 218);

    // 6. Subtitle Tag
    pipCtx.font = '12px Inter, sans-serif';
    pipCtx.fillStyle = 'rgba(255, 255, 255, 0.4)';
    pipCtx.fillText('Digital Countdown • Android Box Ô Tô', w / 2, 248);
}

async function togglePiP(e) {
    if (e && e.stopPropagation) e.stopPropagation();

    initPiPElements();

    if (document.pictureInPictureElement) {
        try {
            await document.exitPictureInPicture();
        } catch(err) {}
    } else {
        if (!document.pictureInPictureEnabled && !pipVideo.requestPictureInPicture) {
            alert("Trình duyệt Android Box không hỗ trợ Picture-in-Picture (PiP). Vui lòng dùng Chrome hoặc Kiwi Browser.");
            return;
        }

        try {
            renderPiPCanvas();

            if (!pipVideo.srcObject) {
                const stream = pipCanvas.captureStream(30);
                pipVideo.srcObject = stream;
            }

            await pipVideo.play();
            await pipVideo.requestPictureInPicture();

            if ('mediaSession' in navigator) {
                try {
                    navigator.mediaSession.metadata = new MediaMetadata({
                        title: `Đếm ngược: ${countdownTime}s`,
                        artist: 'Android Box Ô Tô',
                        album: 'Digital Floating Countdown'
                    });
                    navigator.mediaSession.setActionHandler('play', () => { startTimer(); });
                    navigator.mediaSession.setActionHandler('pause', () => { pauseTimer(); });
                } catch(mErr) {}
            }
        } catch(err) {
            console.error("Lỗi PiP:", err);
            alert("Vui lòng chạm tương tác với màn hình 1 lần trước khi bấm nút Cửa Sổ Nổi PiP.");
        }
    }
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}

