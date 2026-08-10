/**
 * AI VietMap Camera Red Light HUD Floating Overlay Engine
 * Dynamic Theme & Audio Rules:
 * - 30s, 20s, 10s -> Tik-Tik (2 consecutive ticks)
 * - 5s down to 1s -> Tik (1 tick per second)
 * - > 10s: BLUE digits & ring
 * - 10s - 6s: YELLOW digits & ring
 * - 5s - 1s: RED digits & ring
 * - 0s: GREEN digits & ring + Finish Music + Auto-hide
 */

const safeStorage = {
    getItem: function(key, defaultVal = null) {
        try {
            const val = localStorage.getItem(key);
            return val !== null ? val : defaultVal;
        } catch(e) {
            return defaultVal;
        }
    },
    setItem: function(key, val) {
        try { localStorage.setItem(key, val); } catch(e) {}
    }
};

let countdownTime = 0;
let initialSetTime = 30;
let targetEndTime = 0;
let isRunning = false;
let audioEnabled = safeStorage.getItem('openclaw_countdown_audio') !== 'false';
let audioCtx = null;
let tickerTimer = null;
let lastBeepedMilestone = -1;

let floatingWidget, widgetHeader, digitalDisplay, digit1, digit2;
let statusText, statusIcon, statusBadge, progressFill, audioBtn;

function getElements() {
    floatingWidget = document.getElementById('floatingWidget');
    widgetHeader = document.getElementById('widgetHeader');
    digitalDisplay = document.getElementById('digitalDisplay');
    digit1 = document.getElementById('digit1');
    digit2 = document.getElementById('digit2');
    statusText = document.getElementById('statusText');
    statusIcon = document.getElementById('statusIcon');
    statusBadge = document.getElementById('statusBadge');
    progressFill = document.getElementById('progressFill');
    audioBtn = document.getElementById('audioBtn');
}

function playBeep(freq = 1400, duration = 0.09, type = 'sine', volume = 0.7) {
    if (!audioEnabled) return;
    try {
        const AudioCtxClass = window.AudioContext || window.webkitAudioContext;
        if (!audioCtx && AudioCtxClass) audioCtx = new AudioCtxClass();
        if (audioCtx) {
            const runBeep = () => {
                const now = audioCtx.currentTime + 0.02;
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
            };

            if (audioCtx.state === 'suspended') {
                audioCtx.resume().then(runBeep).catch(runBeep);
            } else {
                runBeep();
            }
        }
    } catch(e) {}
}

function playDoubleTing() {
    if (!audioEnabled) return;
    try {
        const AudioCtxClass = window.AudioContext || window.webkitAudioContext;
        if (!audioCtx && AudioCtxClass) audioCtx = new AudioCtxClass();
        if (audioCtx) {
            const runDoubleBeep = () => {
                const now = audioCtx.currentTime + 0.03;
                const osc1 = audioCtx.createOscillator();
                const gain1 = audioCtx.createGain();
                osc1.type = 'sine';
                osc1.frequency.setValueAtTime(1400, now);
                gain1.gain.setValueAtTime(0.7, now);
                gain1.gain.linearRampToValueAtTime(0.001, now + 0.09);
                osc1.connect(gain1);
                gain1.connect(audioCtx.destination);
                osc1.start(now);
                osc1.stop(now + 0.09);

                const osc2 = audioCtx.createOscillator();
                const gain2 = audioCtx.createGain();
                osc2.type = 'sine';
                osc2.frequency.setValueAtTime(1600, now + 0.16);
                gain2.gain.setValueAtTime(0.75, now + 0.16);
                gain2.gain.linearRampToValueAtTime(0.001, now + 0.25);
                osc2.connect(gain2);
                gain2.connect(audioCtx.destination);
                osc2.start(now + 0.16);
                osc2.stop(now + 0.25);
            };

            if (audioCtx.state === 'suspended') {
                audioCtx.resume().then(runDoubleBeep).catch(runDoubleBeep);
            } else {
                runDoubleBeep();
            }
        }
    } catch(e) {}
}

function playSingleTick() {
    playBeep(1400, 0.09, 'sine', 0.7);
}

function playFinishAlarm() {
    if (!audioEnabled) return;
    try {
        const notes = [880, 1108.73, 1318.51, 1760];
        notes.forEach((freq, idx) => {
            setTimeout(() => {
                playBeep(freq, 0.18, 'triangle', 0.75);
            }, idx * 110);
        });
    } catch(e) {}
}

function updateDisplay() {
    const formatted = String(Math.max(0, countdownTime)).padStart(2, '0');
    if (digit1) digit1.textContent = formatted[0];
    if (digit2) digit2.textContent = formatted[1];

    if (progressFill && initialSetTime > 0) {
        const maxOffset = 477.5;
        const ratio = Math.max(0, Math.min(1, countdownTime / initialSetTime));
        const offset = maxOffset * (1 - ratio);
        progressFill.style.strokeDashoffset = offset;
    }

    // Dynamic Color Rule: > 10s BLUE | 10s - 6s YELLOW | 5s - 1s RED | 0s GREEN
    if (floatingWidget) {
        floatingWidget.classList.remove('theme-blue', 'theme-yellow', 'theme-red', 'theme-green');
        if (countdownTime > 10) {
            floatingWidget.classList.add('theme-blue');
        } else if (countdownTime >= 6 && countdownTime <= 10) {
            floatingWidget.classList.add('theme-yellow');
        } else if (countdownTime >= 1 && countdownTime <= 5) {
            floatingWidget.classList.add('theme-red');
        } else if (countdownTime === 0) {
            floatingWidget.classList.add('theme-green');
        }
    }
}

function tick() {
    if (!isRunning) return;
    const now = Date.now();
    const calcRem = Math.max(0, Math.ceil((targetEndTime - now) / 1000));

    if (calcRem !== countdownTime) {
        const prevTime = countdownTime;
        countdownTime = calcRem;
        updateDisplay();

        // Audio Trigger Rules: 30s, 20s, 10s -> Tik-Tik | 5s-1s -> Tik 1/s
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
    }

    if (countdownTime <= 0) {
        stopTimer();
        playFinishAlarm();

        if (statusText) statusText.textContent = "ĐÈN XANH - ĐƯỢC PHÉP ĐI";
        if (statusIcon) statusIcon.textContent = "🟢";

        setTimeout(() => {
            if (floatingWidget) floatingWidget.classList.add('hidden');
        }, 800);
    }
}

function startTimer() {
    if (countdownTime <= 0) return;
    targetEndTime = Date.now() + (countdownTime * 1000);
    isRunning = true;

    if (statusText) statusText.textContent = "ĐÈN ĐỎ AI - ĐANG ĐẾM";
    if (statusIcon) statusIcon.textContent = "🛑";

    // Initial audio trigger rule
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
    if (tickerTimer) clearInterval(tickerTimer);
    tickerTimer = setInterval(tick, 150);
}

function stopTimer() {
    if (tickerTimer) clearInterval(tickerTimer);
    tickerTimer = null;
    isRunning = false;
}

function makeDraggable(element, handle) {
    let isDragging = false;
    let startX = 0, startY = 0;
    let initialX = 20, initialY = 20;

    const onStart = (e) => {
        isDragging = true;
        element.classList.add('is-dragging');
        const clientX = e.type.includes('touch') ? e.touches[0].clientX : e.clientX;
        const clientY = e.type.includes('touch') ? e.touches[0].clientY : e.clientY;
        startX = clientX - initialX;
        startY = clientY - initialY;
    };

    const onMove = (e) => {
        if (!isDragging) return;
        const clientX = e.type.includes('touch') ? e.touches[0].clientX : e.clientX;
        const clientY = e.type.includes('touch') ? e.touches[0].clientY : e.clientY;
        initialX = clientX - startX;
        initialY = clientY - startY;
        element.style.left = initialX + 'px';
        element.style.top = initialY + 'px';
    };

    const onEnd = () => {
        isDragging = false;
        element.classList.remove('is-dragging');
    };

    handle.addEventListener('mousedown', onStart);
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onEnd);

    handle.addEventListener('touchstart', onStart, { passive: true });
    document.addEventListener('touchmove', onMove, { passive: true });
    document.addEventListener('touchend', onEnd);
}

function handleToggleAudio(e) {
    if (e) e.stopPropagation();
    audioEnabled = !audioEnabled;
    safeStorage.setItem('openclaw_countdown_audio', audioEnabled);
    if (audioBtn) audioBtn.textContent = audioEnabled ? '🔊' : '🔇';
}

function handleCloseApp(e) {
    if (e) e.stopPropagation();
    if (floatingWidget) floatingWidget.classList.add('hidden');
    if (window.AndroidBridge && window.AndroidBridge.closeApp) {
        window.AndroidBridge.closeApp();
    }
}

function toggleStatusPanel(e) {
    if (e) e.stopPropagation();
    const card = document.getElementById('statusExpandedCard');
    if (card) {
        card.classList.toggle('hidden');
    }
}

function checkEnvironment() {
    if (window.AndroidBridge || location.protocol === 'file:') {
        document.body.classList.add('is-android-app');
    }
}

window.updateSystemStatus = function(wifiSsid, streamStatus, gpsSpeed, aiStatus) {
    const valWifi = document.getElementById('valWifi');
    const valStream = document.getElementById('valStream');
    const valGps = document.getElementById('valGps');
    const valAi = document.getElementById('valAi');

    if (valWifi && wifiSsid) valWifi.textContent = wifiSsid;
    if (valStream && streamStatus) valStream.textContent = streamStatus;
    if (valGps && gpsSpeed !== undefined) valGps.textContent = gpsSpeed;
    if (valAi && aiStatus) valAi.textContent = aiStatus;
};

function init() {
    getElements();
    checkEnvironment();
    if (widgetHeader && floatingWidget) {
        makeDraggable(floatingWidget, widgetHeader);
    }
    if (audioBtn) {
        audioBtn.textContent = audioEnabled ? '🔊' : '🔇';
    }
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}

window.setCameraCountdown = function(seconds) {
    if (typeof seconds !== 'number' || seconds < 10 || seconds > 99) {
        console.log("[VietMap AI HUD] Ignored initial countdown < 10s or invalid:", seconds);
        return;
    }
    console.log("[VietMap AI HUD] Camera detected Red Light countdown seconds:", seconds);

    countdownTime = seconds;
    initialSetTime = seconds;

    if (floatingWidget) {
        floatingWidget.classList.remove('hidden');
    }

    startTimer();
};

window.onVehicleMoved = function() {
    console.log("[VietMap AI HUD] Vehicle moved - hiding HUD");
    stopTimer();
    if (floatingWidget) {
        floatingWidget.classList.add('hidden');
    }
};

window.onRedLightEnded = function() {
    console.log("[VietMap AI HUD] Red light ended");
    stopTimer();
    countdownTime = 0;
    updateDisplay();
    if (statusText) statusText.textContent = "ĐÈN XANH - ĐƯỢC PHÉP ĐI";
    if (statusIcon) statusIcon.textContent = "🟢";

    playFinishAlarm();
    setTimeout(() => {
        if (floatingWidget) floatingWidget.classList.add('hidden');
    }, 800);
};
