// =========================================================================
// ISKRA MODULE: CORE APP CONTROLLER, HARDWARE BRIDGE & TAB NAVIGATION
// =========================================================================

var S = null;
window.S = null;
var cooldownSec = 20;
window.cooldownSec = 20;

const TAB_ORDER = ['ctrl', 'grafana', 'dtek', 'bunker', 'gen'];
let activeTabId = 'ctrl';

function switchTab(id, swipeDir) {
  if (!TAB_ORDER.includes(id)) return;
  activeTabId = id;
  const offset = swipeDir === 'next' ? '18px' : (swipeDir === 'prev' ? '-18px' : '0px');
  document.documentElement.style.setProperty('--swipe-dir', offset);

  TAB_ORDER.forEach(t => {
    const vEl = document.getElementById('view-' + t);
    const nEl = document.getElementById('nav-' + t);
    if (vEl) vEl.classList.toggle('active', t === id);
    if (nEl) nEl.classList.toggle('active', t === id);
  });
  window.scrollTo(0, 0);
  if (id === 'grafana' && S) {
    requestAnimationFrame(() => renderGrafanaPanels(S));
  } else if (id === 'dtek' && S) {
    requestAnimationFrame(() => renderDtekSchedule(S));
  } else if (id === 'gen' && S) {
    requestAnimationFrame(() => {
      renderGeneratorTab(S);
      if (typeof renderAutomationUI === 'function') renderAutomationUI(S);
    });
  }
}

// Horizontal Swipe Gesture Navigation across all 5 tabs
(function initSwipeNavigation() {
  let startX = 0, startY = 0, tracking = false;
  document.addEventListener('touchstart', e => {
    if (!e.touches || e.touches.length !== 1) return;
    const tag = (e.target && e.target.tagName) ? e.target.tagName.toLowerCase() : '';
    if (tag === 'input' || tag === 'select') {
      tracking = false;
      return;
    }
    startX = e.touches[0].clientX;
    startY = e.touches[0].clientY;
    tracking = true;
  }, { passive: true });

  document.addEventListener('touchend', e => {
    if (!tracking || !e.changedTouches || e.changedTouches.length !== 1) return;
    tracking = false;
    const dx = e.changedTouches[0].clientX - startX;
    const dy = e.changedTouches[0].clientY - startY;
    if (Math.abs(dx) > 56 && Math.abs(dx) > Math.abs(dy) * 1.35) {
      const curIdx = TAB_ORDER.indexOf(activeTabId);
      if (dx < 0 && curIdx < TAB_ORDER.length - 1) {
        switchTab(TAB_ORDER[curIdx + 1], 'next');
      } else if (dx > 0 && curIdx > 0) {
        switchTab(TAB_ORDER[curIdx - 1], 'prev');
      }
    }
  }, { passive: true });
})();

function forceRefresh() {
  if (window.IskraNative) window.IskraNative.forceRefresh();
}

function resetHistory() {
  if (window.IskraNative) window.IskraNative.resetHistory();
}

function setCooldownSec(sec) {
  cooldownSec = sec;
  window.cooldownSec = sec;
  [15, 20, 30, 60].forEach(v => {
    const el = document.getElementById('cd' + v);
    if (el) el.classList.toggle('active', v === sec);
  });
  const subEl = document.getElementById('killSubText');
  if (subEl) {
    subEl.textContent = isEn()
      ? `Socket OFF -> Idle ${sec}s -> Shelly Hold`
      : `Выкл розетку -> Холостой ${sec}с -> Shelly`;
  }
}

let stepsInitialized = false;
function getCustomSteps() {
  try {
    const raw = localStorage.getItem('iskra_charge_steps');
    if (raw) {
      const arr = JSON.parse(raw);
      if (Array.isArray(arr) && arr.length === 5) {
        return arr.map(x => Math.max(0, Math.min(100, Math.round(Number(x) || 0))));
      }
    }
  } catch (e) {}
  return [0, 25, 45, 75, 100];
}

function stepWattsLabel(pct) {
  if (pct <= 0) return '85W';
  const w = Math.round(pct * 12);
  return w >= 1000 ? `${(w / 1000).toFixed(1)}kW` : `${w}W`;
}

function syncStepsUI(curLim) {
  const steps = getCustomSteps();
  if (!stepsInitialized) {
    stepsInitialized = true;
    steps.forEach((v, idx) => {
      const inp = document.getElementById('inpStep' + idx);
      if (inp) inp.value = v;
    });
  }
  const metaEl = document.getElementById('stepsSavedMeta');
  if (metaEl && !metaEl.dataset.flashing) {
    metaEl.textContent = steps.join(' / ');
  }

  steps.forEach((v, idx) => {
    const btn = document.getElementById('stBtn' + idx);
    if (btn) {
      btn.textContent = `${v}% (${stepWattsLabel(v)})`;
      btn.classList.toggle('active', Math.abs((curLim ?? 45) - v) <= 3);
    }
  });

  // Sync top 3 quick profile labels with Step 2 (Warmup), Step 3 (Generator), Step 5 (Grid)
  const sWarm = steps[1] ?? 25;
  const sGen  = steps[2] ?? 45;
  const sMax  = steps[4] ?? 100;
  const lblW = document.getElementById('lblModeWarmup');
  const subW = document.getElementById('subModeWarmup');
  const lblG = document.getElementById('lblModeRato');
  const subG = document.getElementById('subModeRato');
  const lblM = document.getElementById('lblModeMax');
  const subM = document.getElementById('subModeMax');
  if (lblW) lblW.textContent = isEn() ? `1. WARMUP (${sWarm}%)` : `1. ПРОГРЕВ (${sWarm}%)`;
  if (subW) subW.textContent = isEn() ? `First 10-15m (~${stepWattsLabel(sWarm)} to batt)` : `Первые 10-15 мин (~${stepWattsLabel(sWarm)} в бат.)`;
  if (lblG) lblG.textContent = isEn() ? `2. GENERATOR (${sGen}%)` : `2. ОТ ГЕНЕРАТОРА (${sGen}%)`;
  if (subG) subG.textContent = isEn()
    ? (sGen <= 45 ? `Gaming safe (~${stepWattsLabel(sGen)} to batt)` : `Standard PC (~${stepWattsLabel(sGen)} to batt)`)
    : (sGen <= 45 ? `Под игру без рывков (~${stepWattsLabel(sGen)} в бат.)` : `Рабочий с ПК (~${stepWattsLabel(sGen)} в бат.)`);
  if (lblM) lblM.textContent = isEn() ? `3. GRID FAST (${sMax}%)` : `3. ОТ СЕТИ (${sMax}%)`;
  if (subM) subM.textContent = isEn() ? `Max DTEK (~${stepWattsLabel(sMax)} to batt)` : `Максимум ДТЕК (~${stepWattsLabel(sMax)} в бат.)`;
}

function clickStepIdx(idx) {
  const steps = getCustomSteps();
  const val = steps[idx] ?? 45;
  setChargeLimit(val);
}

function applyPresetSteps(arr) {
  arr.forEach((v, idx) => {
    const inp = document.getElementById('inpStep' + idx);
    if (inp) inp.value = v;
  });
  saveCustomSteps();
}

function saveCustomSteps() {
  const next = [0, 1, 2, 3, 4].map(idx => {
    const inp = document.getElementById('inpStep' + idx);
    const val = parseInt(inp ? inp.value : '0', 10);
    return isNaN(val) ? 0 : Math.max(0, Math.min(100, val));
  });
  localStorage.setItem('iskra_charge_steps', JSON.stringify(next));
  const metaEl = document.getElementById('stepsSavedMeta');
  if (metaEl) {
    metaEl.dataset.flashing = '1';
    metaEl.textContent = isEn() ? '✓ SAVED' : '✓ СОХРАНЕНО';
    metaEl.style.color = 'var(--green)';
    setTimeout(() => {
      delete metaEl.dataset.flashing;
      metaEl.textContent = next.join(' / ');
      metaEl.style.color = 'var(--dim)';
    }, 2000);
  }
  syncStepsUI(S && S.oukitel ? S.oukitel.acChargingLimit : 45);
}

function setChargeLimit(val) {
  if (window.IskraNative) window.IskraNative.setOukitelChargeLimit(val);
}

function setChargeProfile(profile) {
  if (!window.IskraNative) return;
  const steps = getCustomSteps();
  if (profile === 'IDLE') {
    window.IskraNative.setOukitelChargeLimit(steps[0] ?? 0);
  } else if (profile === 'WARMUP') {
    window.IskraNative.setOukitelChargeLimit(steps[1] ?? 25);
  } else if (profile === 'RATO') {
    window.IskraNative.setOukitelChargeLimit(steps[2] ?? 45);
  } else if (profile === 'MAX') {
    window.IskraNative.setOukitelChargeLimit(steps[4] ?? 100);
  }
}

function togglePlug(devId, key) {
  if (!S || !window.IskraNative) return;
  const cur = key === 's2' ? S.smartLife.socket2.switchOn : S.smartLife.socket1.switchOn;
  window.IskraNative.toggleSmartLifeSwitch(devId, 'switch_1', !cur);
}

function toggleOukAc() {
  if (!S || !window.IskraNative) return;
  window.IskraNative.toggleOukitelAc(!S.oukitel.acSwitch);
}

function toggleOukDc() {
  if (!S || !window.IskraNative) return;
  window.IskraNative.toggleOukitelDc(!S.oukitel.dcSwitch);
}

function toggleOukLed() {
  if (!S || !window.IskraNative) return;
  const next = S.oukitel.ledStatus > 0 ? 0 : 1;
  window.IskraNative.toggleOukitelLed(next);
}

function startSmartStop() {
  if (window.IskraNative) window.IskraNative.startSmartRatoStop(cooldownSec);
}

function cancelStop() {
  if (window.IskraNative) window.IskraNative.cancelRatoStop();
}

function killNow() {
  if (window.IskraNative) window.IskraNative.killRatoNow();
}

function toggleSnek(code) {
  if (!S || !window.IskraNative) return;
  const sk = S.smartLife.snek;
  const map = {
    'switch_1': sk.light,
    'switch_2': sk.heat,
    'switch_3': sk.wifiLamp,
    'switch_4': sk.laptop
  };
  window.IskraNative.toggleSmartLifeSwitch('snek_strip', code, !map[code]);
}

function toggleRoomDev(devId, code, key) {
  if (!S || !window.IskraNative) return;
  const sl = S.smartLife;
  const cur = key === 'backlight' ? sl.backlightOn : (key === 'ledbulb' ? sl.ledBulbOn : sl.purifierOn);
  window.IskraNative.toggleSmartLifeSwitch(devId, code, !cur);
}

window.onIskraState = function(data) {
  S = (typeof data === 'string') ? JSON.parse(data) : data;
  window.S = S;
  renderAll(S);
};

function renderAll(d) {
  const ouk = d.oukitel || {};
  const rato = d.rato || {};
  const sl = d.smartLife || {};

  // Top Navbar
  const led = document.getElementById('navLed');
  const modeTag = document.getElementById('navModeTag');
  if (d.currentMode === 'GENERATOR') {
    led.style.background = 'var(--amber)';
    modeTag.textContent = isEn() ? 'RATO GENSET' : 'ОТ ГЕНЕРАТОРА RATO';
    modeTag.style.color = 'var(--amber)';
  } else if (d.currentMode === 'GRID') {
    led.style.background = 'var(--green)';
    modeTag.textContent = isEn() ? 'DTEK GRID' : 'ОТ СЕТИ ДТЕК';
    modeTag.style.color = 'var(--green)';
  } else if (d.currentMode === 'BLACKOUT_ZERO') {
    led.style.background = 'var(--zero)';
    modeTag.textContent = isEn() ? 'BLACKOUT 0%' : 'ВСЕ В 0% / ОФФЛАЙН';
    modeTag.style.color = 'var(--muted)';
  } else {
    led.style.background = 'var(--red)';
    modeTag.textContent = isEn() ? 'BATTERY UPS' : 'НА СТАНЦИИ (БЕЗ СЕТИ)';
    modeTag.style.color = 'var(--red)';
  }

  const netTag = document.getElementById('navNetTag');
  if (d.internetOnline) {
    netTag.textContent = isEn() ? 'WAN ONLINE' : 'ИHТЕРНЕТ ОК';
    netTag.style.color = 'var(--green)';
  } else {
    netTag.textContent = isEn() ? 'OFFLINE LAN' : 'БЕЗ ИНТЕРНЕТА (LAN)';
    netTag.style.color = 'var(--amber)';
  }

  const coPpm = (sl.co && sl.co.ppm) || 0;
  const navCo = document.getElementById('navCoTag');
  navCo.textContent = `CO: ${coPpm} ppm`;
  navCo.style.color = coPpm > 15 ? 'var(--red)' : 'var(--green)';

  const dtNow = new Date(d.timestamp || Date.now());
  document.getElementById('btnSync').textContent =
    String(dtNow.getHours()).padStart(2,'0') + ':' +
    String(dtNow.getMinutes()).padStart(2,'0') + ':' +
    String(dtNow.getSeconds()).padStart(2,'0');

  // Stat Cell 1: Oukitel SOC
  const pct = ouk.batteryPct || 0;
  document.getElementById('stOukConn').textContent = ouk.online ? ouk.connectionMode.toUpperCase() : (isEn() ? 'OFFLINE' : 'ОФФЛАЙН');
  document.getElementById('stBattPct').textContent = `${pct}%`;
  document.getElementById('stBattBar').style.width = `${pct}%`;
  const eta = ouk.totalInputW > ouk.totalOutputW
    ? `${isEn() ? 'CHG' : 'ЗАРЯД'}: ~${fmtMin(ouk.remainChargeMinutes || ouk.remainMinutes)} • ${ouk.tempC}°C`
    : `${isEn() ? 'DISCHG' : 'РАЗРЯД'}: ~${fmtMin(ouk.remainMinutes)} • ${ouk.tempC}°C`;
  document.getElementById('stBattEta').textContent = eta;

  // Stat Cell 2: Power Balance
  const inW = ouk.totalInputW || 0;
  const outW = ouk.totalOutputW || 0;
  const netW = inW - outW;
  document.getElementById('stInW').textContent = `${inW}W`;
  document.getElementById('stOutW').textContent = `${outW}W`;
  const netEl = document.getElementById('stNetW');
  netEl.textContent = (netW >= 0 ? `+${netW} W` : `${netW} W`);
  netEl.style.color = netW >= 0 ? 'var(--green)' : 'var(--amber)';
  document.getElementById('stFlowSub').textContent = isEn()
    ? `TO BATT: ${ouk.acInputW || 0}W • HOME: ${outW}W`
    : `В БАТАРЕЮ: ${ouk.acInputW || 0}W • ДОМ: ${outW}W`;
  const loadPct = Math.min(100, Math.round((Math.max(inW, outW) / 1800) * 100));
  document.getElementById('stLoadBar').style.width = `${loadPct}%`;

  // Stat Cell 3: DTEK Grid (socket_1)
  const gridUp = !!sl.gridOnline;
  const gState = document.getElementById('stGridState');
  const gTitle = document.getElementById('stGridTitle');
  const gSub = document.getElementById('stGridSub');
  if (gridUp) {
    gState.textContent = 'ONLINE';
    gState.className = 'tag-online';
    gTitle.textContent = isEn() ? 'GRID ONLINE' : 'СВЕТ ЕСТЬ';
    gTitle.style.color = 'var(--green)';
    const v1 = (sl.socket1 && sl.socket1.voltageV) ? `${sl.socket1.voltageV.toFixed(1)} V • ${sl.socket1.powerW.toFixed(0)} W` : `In ${inW} W`;
    gSub.textContent = `socket_1: ${v1}`;
  } else {
    gState.textContent = 'OFFLINE';
    gState.className = 'tag-offline';
    gTitle.textContent = isEn() ? 'BLACKOUT' : 'НЕТ СВЕТА';
    gTitle.style.color = 'var(--red)';
    gSub.textContent = isEn()
      ? `Outage: ${fmtDur(d.darkDurationSec)} (socket_1 0V)`
      : `Вне сети: ${fmtDur(d.darkDurationSec)} (socket_1 0V)`;
  }

  // Stat Cell 4: Current Phase
  if (d.currentMode === 'GRID') {
    document.getElementById('stCyclePct').textContent = `${pct}%`;
    document.getElementById('stCyclePct').style.color = 'var(--green)';
    document.getElementById('stCycleTime').textContent = pct >= 99
      ? (isEn() ? 'CHARGED 100%' : 'ЗАРЯЖЕНО 100%')
      : `~${fmtMin(ouk.remainChargeMinutes || ouk.remainMinutes)} ${isEn() ? 'to 100%' : 'до 100%'}`;
    document.getElementById('stCycleTime').style.color = 'var(--green)';
    document.getElementById('stCycleBar').style.width = `${pct}%`;
    document.getElementById('stCycleBar').style.background = 'var(--green)';
    document.getElementById('stCycleSub').textContent = isEn()
      ? `On DTEK Grid for: ${fmtDur(d.modeDurationSec)}`
      : `От сети ДТЕК уже: ${fmtDur(d.modeDurationSec)}`;
  } else {
    const cycPct = d.cycleProgressPct || 0;
    const cycMin = Math.floor((d.cycleElapsedSec || 0) / 60);
    const cycTarget = Math.floor((d.cycleTargetSec || 3600) / 60);
    const uM = isEn() ? 'm' : 'м';
    document.getElementById('stCyclePct').textContent = `${cycPct}%`;
    document.getElementById('stCyclePct').style.color = 'var(--amber)';
    document.getElementById('stCycleTime').textContent = `${cycMin}${uM} / ${cycTarget}${uM}`;
    document.getElementById('stCycleTime').style.color = 'var(--amber)';
    document.getElementById('stCycleBar').style.width = `${cycPct}%`;
    document.getElementById('stCycleBar').style.background = 'var(--amber)';
    document.getElementById('stCycleSub').textContent = isEn()
      ? (d.currentMode === 'GENERATOR' ? 'Phase: Rato Charging (60m target)' : 'Phase: Battery UPS (120m target)')
      : (d.currentMode === 'GENERATOR' ? 'Фаза: Зарядка от Rato (цель 60м)' : 'Фаза: На станции (цель 120м)');
  }

  // Smart Sockets Routing (socket_2 strictly 0 when Shelly is offline!)
  if (sl.socket2) {
    const s2 = sl.socket2;
    const s2St = document.getElementById('s2Status');
    s2St.textContent = s2.online ? (s2.switchOn ? 'ON' : 'OFF') : (isEn() ? 'UNPOWERED' : 'ОБЕСТОЧЕН');
    s2St.className = (s2.online && s2.switchOn) ? 'tag-online' : 'tag-offline';
    document.getElementById('s2Metrics').textContent = s2.online
      ? `${s2.voltageV.toFixed(1)} V • ${s2.powerW.toFixed(0)} W`
      : '0.0 V • 0 W';
    document.getElementById('s2Metrics').style.color = s2.online ? 'var(--text)' : 'var(--muted)';
    const b2 = document.getElementById('btnSocket2');
    b2.className = 'ctrl-btn ' + (s2.online && s2.switchOn ? 'active-amber' : '');
    document.getElementById('btnSocket2Txt').textContent = isEn()
      ? (s2.online ? `RATO LINE: ${s2.switchOn ? 'ON' : 'OFF'}` : 'RATO LINE: UNPOWERED')
      : (s2.online ? `ЛИНИЯ RATO: ${s2.switchOn ? 'ВКЛ' : 'ВЫКЛ'}` : 'ЛИНИЯ RATO: ОБЕСТОЧЕНА');
  }

  if (sl.socket1) {
    const s1 = sl.socket1;
    const s1St = document.getElementById('s1Status');
    s1St.textContent = s1.online ? (s1.switchOn ? 'ON' : 'OFF') : (isEn() ? 'OFFLINE' : 'ОБЕСТОЧЕН');
    s1St.className = s1.online ? 'tag-online' : 'tag-offline';
    document.getElementById('s1Metrics').textContent = s1.online
      ? `${s1.voltageV.toFixed(1)} V • ${s1.powerW.toFixed(0)} W`
      : '0.0 V • 0 W';
    document.getElementById('s1Metrics').style.color = s1.online ? 'var(--text)' : 'var(--muted)';
    const b1 = document.getElementById('btnSocket1');
    b1.className = 'ctrl-btn ' + (s1.online && s1.switchOn ? 'active-green' : '');
    document.getElementById('btnSocket1Txt').textContent = isEn()
      ? (s1.online ? `DTEK GRID: ${s1.switchOn ? 'ON' : 'OFF'}` : 'DTEK GRID: OFFLINE')
      : (s1.online ? `СЕТЬ ДТЕК: ${s1.switchOn ? 'ВКЛ' : 'ВЫКЛ'}` : 'СЕТЬ ДТЕК: ОБЕСТОЧЕНА');
  }

  // Charging Limit Buttons
  const lim = ouk.acChargingLimit ?? 45;
  document.getElementById('curLimitLabel').textContent = isEn()
    ? `LIMIT: ${lim}% (TO BATT ${ouk.acInputW || 0}W)`
    : `ЛИМИТ: ${lim}% (В БАТАРЕЮ ${ouk.acInputW || 0}W)`;
  document.getElementById('btnModeIdle').className = 'ctrl-btn ' + (lim > 0 && lim <= 30 ? 'active-blue' : '');
  document.getElementById('btnModeRato').className = 'ctrl-btn ' + (lim > 30 && lim <= 80 ? 'active-amber' : '');
  document.getElementById('btnModeMax').className  = 'ctrl-btn ' + (lim > 80 ? 'active-green' : '');

  syncStepsUI(lim);

  const onWord = isEn() ? 'ENABLED' : 'ВКЛЮЧЕНО';
  const offWord = isEn() ? 'OFF' : 'ВЫКЛ';
  // Oukitel Outputs
  document.getElementById('oukOutMeta').textContent = `AC: ${ouk.acOutputW || outW}W • DC: ${ouk.dcOutputW || 0}W`;
  document.getElementById('btnOukAc').className = 'ctrl-btn ' + (ouk.acSwitch ? 'active-blue' : '');
  document.getElementById('subOukAc').textContent = ouk.acSwitch ? onWord : offWord;
  document.getElementById('btnOukDc').className = 'ctrl-btn ' + (ouk.dcSwitch ? 'active-blue' : '');
  document.getElementById('subOukDc').textContent = ouk.dcSwitch ? onWord : offWord;
  document.getElementById('btnOukLed').className = 'ctrl-btn ' + (ouk.ledStatus > 0 ? 'active-amber' : '');
  document.getElementById('subOukLed').textContent = ouk.ledStatus > 0 ? onWord : offWord;

  // Generator Rato & Shelly
  document.getElementById('shellyMeta').textContent = isEn()
    ? `${rato.shellyIp} • ${rato.shellyOnline ? 'SHELLY ONLINE' : 'SHELLY OFFLINE'}`
    : `${rato.shellyIp} • ${rato.shellyOnline ? 'SHELLY В СЕТИ' : 'SHELLY ОБЕСТОЧЕН'}`;
  const rSt = document.getElementById('ratoState');
  rSt.textContent = rato.running ? (isEn() ? 'RUNNING' : 'РАБОТАЕТ') : (isEn() ? 'STOPPED' : 'ЗАГЛУШЕН');
  rSt.style.color = rato.running ? 'var(--amber)' : 'var(--muted)';
  document.getElementById('ratoUptime').textContent = rato.running ? fmtDur(rato.uptimeSec) : (isEn() ? '0s' : '0с');
  document.getElementById('ratoTemp').textContent = `${(rato.tempC || 0).toFixed(1)}°C`;
  document.getElementById('ratoMoto').textContent = `${rato.motoHours || 0} ${isEn() ? 'h' : 'ч'}`;

  // Cooldown State Machine
  const phase = rato.shutdownPhase || 'IDLE';
  const cdBox = document.getElementById('cooldownBox');
  if (phase !== 'IDLE') {
    cdBox.classList.add('active');
    document.getElementById('cdPhaseTitle').textContent = `${isEn() ? 'STOP PHASE' : 'СТАТУС СТОПА'}: ${phase}`;
    document.getElementById('cdCountdown').textContent = `${rato.shutdownRemainingSec || 0}s`;
    document.getElementById('cdMsg').textContent = rato.shutdownMessage || '';
    const pctCd = rato.shutdownTotalSec > 0
      ? Math.round((rato.shutdownRemainingSec / rato.shutdownTotalSec) * 100)
      : 0;
    document.getElementById('cdBar').style.width = `${pctCd}%`;
  } else {
    cdBox.classList.remove('active');
  }

  // Bunker & Snek Tab
  if (sl.snek) {
    const sk = sl.snek;
    document.getElementById('snekOnlineMeta').textContent = sk.online ? 'ONLINE' : 'OFFLINE';
    const setSw = (id, subId, on, cls) => {
      document.getElementById(id).className = 'ctrl-btn ' + (on ? cls : '');
      document.getElementById(subId).textContent = on ? onWord : offWord;
    };
    setSw('btnSnekHeat', 'subSnekHeat', sk.heat, 'active-amber');
    setSw('btnSnekLight', 'subSnekLight', sk.light, 'active-blue');
    setSw('btnSnekLamp', 'subSnekLamp', sk.wifiLamp, 'active-blue');
    setSw('btnSnekLaptop', 'subSnekLaptop', sk.laptop, 'active-green');
  }

  if (sl.co) {
    document.getElementById('bkCoPpm').textContent = `${sl.co.ppm} ppm`;
    document.getElementById('bkCoBatt').textContent = `${sl.co.batteryPct}%`;
  }
  if (sl.climate) {
    document.getElementById('bkHum').textContent = `${sl.climate.humidityPct.toFixed(0)}%`;
    document.getElementById('bkCo2').textContent = `${sl.climate.co2Ppm} ppm`;
  }
  const shortOn = isEn() ? 'ON' : 'ВКЛ';
  document.getElementById('chandelierMeta').textContent = isEn()
    ? `CHANDELIER: ${sl.chandelierOnlineCount || 0}/4 ONLINE`
    : `ЛЮСТРА: ${sl.chandelierOnlineCount || 0}/4 В СЕТИ`;
  document.getElementById('btnBacklight').className = 'ctrl-btn ' + (sl.backlightOn ? 'active-blue' : '');
  document.getElementById('subBacklight').textContent = sl.backlightOn ? shortOn : offWord;
  document.getElementById('btnLedBulb').className = 'ctrl-btn ' + (sl.ledBulbOn ? 'active-blue' : '');
  document.getElementById('subLedBulb').textContent = sl.ledBulbOn ? shortOn : offWord;
  document.getElementById('btnPurifier').className = 'ctrl-btn ' + (sl.purifierOn ? 'active-green' : '');
  document.getElementById('subPurifier').textContent = sl.purifierOn ? shortOn : offWord;

  if (document.getElementById('view-grafana').classList.contains('active')) {
    renderGrafanaPanels(d);
  }
  renderDtekSchedule(d);
  renderGeneratorTab(d);
  if (typeof renderAutomationUI === 'function') {
    renderAutomationUI(d);
  }
}

// Initial Load + Active 3s Pull & Poll Loop
function pullNativeState() {
  if (!window.IskraNative) return;
  try {
    const json = window.IskraNative.getStateJson();
    if (json) window.onIskraState(json);
  } catch (e) {}
}

window.addEventListener('DOMContentLoaded', () => {
  initDates();
  if (LANG === 'en') setLang('en');
  pullNativeState();
  if (window.IskraNative && window.IskraNative.silentPoll) {
    window.IskraNative.silentPoll();
  }
  setInterval(() => {
    pullNativeState();
    if (!document.hidden && window.IskraNative) {
      if (window.IskraNative.silentPoll) window.IskraNative.silentPoll();
      else window.IskraNative.forceRefresh();
    }
  }, 3000);
});

document.addEventListener('visibilitychange', () => {
  if (!document.hidden) {
    pullNativeState();
    if (window.IskraNative && window.IskraNative.silentPoll) {
      window.IskraNative.silentPoll();
    }
  }
});
