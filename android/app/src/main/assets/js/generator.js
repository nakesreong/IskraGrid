// =========================================================================
// GENERATOR RATO R3000 // MAINTENANCE, PASSPORT & JERRYCAN CALIBRATION
// =========================================================================

let genConfigInitialized = false;
let calibHoursInitialized = false;

function getGenConfig(d) {
  const cfg = (d && d.rato && d.rato.config) ? d.rato.config : {};
  const nomKw = Math.round(Number(cfg.nomKw || 2.7) * 100) / 100;
  const maxKw = Math.round(Number(cfg.maxKw || 3.0) * 100) / 100;
  const tankL = Math.round(Number(cfg.tankL || 14.5) * 100) / 100;
  const oilVolL = Math.round(Number(cfg.oilVolL || 0.55) * 100) / 100;
  const fuelFullLh = Math.round(Number(cfg.fuelFullLh || 1.8) * 100) / 100;
  const fuelHalfLh = Math.round(Number(cfg.fuelHalfLh || 1.2) * 100) / 100;
  const oilIntervalH = Number(cfg.oilIntervalH || 50);
  const sparkIntervalH = Number(cfg.sparkIntervalH || 100);
  const model = cfg.model || 'Rato R3000 (R210 212cc)';

  const idleLh = Math.max(0.25, 2 * fuelHalfLh - fuelFullLh);
  const slopePerKw = Math.max(0.05, (fuelFullLh - idleLh) / Math.max(0.5, nomKw));
  return { model, nomKw, maxKw, tankL, oilVolL, fuelFullLh, fuelHalfLh, oilIntervalH, sparkIntervalH, idleLh, slopePerKw };
}

function calcIntervalFuelLiters(durMs, avgW, cfg) {
  if (!durMs || durMs <= 0) return 0;
  const hours = durMs / 3600_000;
  const w = (avgW && avgW > 40) ? avgW : 880;
  const kw = Math.min(cfg.maxKw, w / 1000.0);
  const rateLh = cfg.idleLh + kw * cfg.slopePerKw;
  return hours * rateLh;
}

function toggleAdjustRow(rowId) {
  const el = document.getElementById(rowId);
  if (el) el.classList.toggle('active');
}

function markOilNow() {
  if (window.IskraNative && window.IskraNative.markOilChanged) {
    window.IskraNative.markOilChanged(0.0);
  }
  const adj = document.getElementById('oilAdjRow');
  if (adj) adj.classList.remove('active');
}

function applyOilCustomHours() {
  const inp = document.getElementById('inpOilUsedH');
  const val = parseFloat(inp ? inp.value : '0') || 0.0;
  if (window.IskraNative && window.IskraNative.markOilChanged) {
    window.IskraNative.markOilChanged(val);
  }
  const adj = document.getElementById('oilAdjRow');
  if (adj) adj.classList.remove('active');
}

function markSparkNow() {
  if (window.IskraNative && window.IskraNative.markSparkChanged) {
    window.IskraNative.markSparkChanged(0.0);
  }
  const adj = document.getElementById('sparkAdjRow');
  if (adj) adj.classList.remove('active');
}

function applySparkCustomHours() {
  const inp = document.getElementById('inpSparkUsedH');
  const val = parseFloat(inp ? inp.value : '0') || 0.0;
  if (window.IskraNative && window.IskraNative.markSparkChanged) {
    window.IskraNative.markSparkChanged(val);
  }
  const adj = document.getElementById('sparkAdjRow');
  if (adj) adj.classList.remove('active');
}

function markTankFullNow() {
  if (window.IskraNative && window.IskraNative.saveGenConfig) {
    window.IskraNative.saveGenConfig(JSON.stringify({ markTankFull: true }));
  }
  const badge = document.getElementById('tankFullBadge');
  if (badge) {
    badge.textContent = isEn() ? '✓ RESET TO 0m' : '✓ СБРОШЕНО В 0м';
    badge.style.color = 'var(--green)';
  }
}

function calibrateByHalfTank() {
  const d = window.S;
  if (!d) return;
  const cfg = getGenConfig(d);
  const rato = d.rato || {};
  const nowMs = d.timestamp || Date.now();
  const tankMarkMs = Number(rato.tankFullMarkMs || nowMs);
  const rawIntervals = (d.history && d.history.intervals) ? d.history.intervals : [];

  let sinceTankMs = 0;
  let estBurnedL = 0;
  rawIntervals.forEach(iv => {
    if (iv.mode === 'GENERATOR') {
      const s = Math.max(tankMarkMs, iv.startMs || 0);
      const e = Math.min(nowMs, iv.endMs || nowMs);
      if (e > s) {
        const dur = e - s;
        const w = (iv.avgInW && iv.avgInW > 40) ? iv.avgInW : ((iv.avgOutW && iv.avgOutW > 40) ? iv.avgOutW : 880);
        sinceTankMs += dur;
        estBurnedL += calcIntervalFuelLiters(dur, w, cfg);
      }
    }
  });

  const statusEl = document.getElementById('calibStatusHint');
  if (sinceTankMs < 1800_000 || estBurnedL <= 0.2) {
    if (statusEl) {
      statusEl.textContent = isEn()
        ? 'Need at least 30+ min of Rato run since full tank mark to calibrate!'
        : 'Для калибровки нужно хотя бы 30+ мин наработки с момента полного бака!';
      statusEl.style.color = 'var(--amber)';
    }
    return;
  }

  const halfTankL = cfg.tankL / 2.0;
  const ratio = Math.max(0.4, Math.min(2.2, halfTankL / estBurnedL));
  const newHalf = Math.round(cfg.fuelHalfLh * ratio * 100) / 100;
  const newFull = Math.round(cfg.fuelFullLh * ratio * 100) / 100;

  const halfInp = document.getElementById('cfgFuelHalf');
  const fullInp = document.getElementById('cfgFuelFull');
  if (halfInp) halfInp.value = newHalf;
  if (fullInp) fullInp.value = newFull;

  saveGenSettings();

  if (statusEl) {
    statusEl.textContent = isEn()
      ? `✓ Calibrated by ½ tank (${halfTankL.toFixed(1)}L over ${fmtMinExact(sinceTankMs)}): ½=${newHalf} L/h, Max=${newFull} L/h`
      : `✓ Откалибровано по ½ бака (${halfTankL.toFixed(1)}л за ${fmtMinExact(sinceTankMs)}): ½=${newHalf} л/ч, Макс=${newFull} л/ч`;
    statusEl.style.color = 'var(--green)';
  }
}

function saveGenSettings() {
  const payload = {
    model: document.getElementById('cfgGenModel').value || 'Rato R3000 (R210 212cc)',
    nomKw: parseFloat(document.getElementById('cfgNomKw').value) || 2.7,
    maxKw: parseFloat(document.getElementById('cfgMaxKw').value) || 3.0,
    fuelFullLh: parseFloat(document.getElementById('cfgFuelFull').value) || 1.8,
    fuelHalfLh: parseFloat(document.getElementById('cfgFuelHalf').value) || 1.2,
    oilVolL: parseFloat(document.getElementById('cfgOilVol').value) || 0.55,
    tankL: parseFloat(document.getElementById('cfgTankL').value) || 14.5,
    oilIntervalH: parseInt(document.getElementById('cfgOilInt').value, 10) || 50,
    sparkIntervalH: parseInt(document.getElementById('cfgSparkInt').value, 10) || 100
  };
  const motoStr = document.getElementById('cfgTotalMotoH').value;
  if (motoStr !== '' && !isNaN(parseFloat(motoStr))) {
    payload.totalMotoHours = parseFloat(motoStr);
    document.getElementById('cfgTotalMotoH').value = '';
  }
  if (window.IskraNative && window.IskraNative.saveGenConfig) {
    window.IskraNative.saveGenConfig(JSON.stringify(payload));
    const nEl = document.getElementById('genSavedNotice');
    if (nEl) {
      nEl.textContent = isEn() ? '✓ SAVED' : '✓ СОХРАНЕНО';
      nEl.style.color = 'var(--green)';
      setTimeout(() => {
        nEl.textContent = payload.model;
        nEl.style.color = 'var(--dim)';
      }, 2500);
    }
  }
}

function renderGeneratorTab(d) {
  if (!d) return;
  const cfg = getGenConfig(d);
  const rato = d.rato || {};
  const hUnit = isEn() ? 'h' : 'ч';

  if (!genConfigInitialized && rato.config) {
    genConfigInitialized = true;
    document.getElementById('cfgGenModel').value = cfg.model;
    document.getElementById('cfgNomKw').value = cfg.nomKw;
    document.getElementById('cfgMaxKw').value = cfg.maxKw;
    document.getElementById('cfgFuelFull').value = cfg.fuelFullLh;
    document.getElementById('cfgFuelHalf').value = cfg.fuelHalfLh;
    document.getElementById('cfgOilVol').value = cfg.oilVolL;
    document.getElementById('cfgTankL').value = cfg.tankL;
    document.getElementById('cfgOilInt').value = cfg.oilIntervalH;
    document.getElementById('cfgSparkInt').value = cfg.sparkIntervalH;
  }

  // Update live Full-Tank Checkpoint status in Tab 5
  const nowMs = d.timestamp || Date.now();
  const tankMarkMs = Number(rato.tankFullMarkMs || nowMs);
  const rawIntervals = (d.history && d.history.intervals) ? d.history.intervals : [];
  let sinceTankMs = 0;
  let sinceTankL = 0;
  rawIntervals.forEach(iv => {
    if (iv.mode === 'GENERATOR') {
      const s = Math.max(tankMarkMs, iv.startMs || 0);
      const e = Math.min(nowMs, iv.endMs || nowMs);
      if (e > s) {
        const dur = e - s;
        const w = (iv.avgInW && iv.avgInW > 40) ? iv.avgInW : ((iv.avgOutW && iv.avgOutW > 40) ? iv.avgOutW : 880);
        sinceTankMs += dur;
        sinceTankL += calcIntervalFuelLiters(dur, w, cfg);
      }
    }
  });
  const hintEl = document.getElementById('calibStatusHint');
  const badgeEl = document.getElementById('tankFullBadge');
  const tDate = rato.tankFullDate || fmtDateDM(tankMarkMs);
  if (badgeEl) {
    badgeEl.textContent = isEn() ? `MARK: ${tDate}` : `МЕТКА: ${tDate}`;
  }
  if (hintEl && !hintEl.textContent.startsWith('✓')) {
    const remL = Math.max(0, cfg.tankL - sinceTankL);
    hintEl.textContent = isEn()
      ? `Since full tank (${tDate}): ran ${fmtMinExact(sinceTankMs)} • burned ~${sinceTankL.toFixed(2)} L (~${remL.toFixed(1)} L left)`
      : `С полного бака (${tDate}): намотано ${fmtMinExact(sinceTankMs)} • сожжено ~${sinceTankL.toFixed(2)} л (в баке ~${remL.toFixed(1)} л)`;
  }

  const totalMotoH = Number(rato.motoHours || 0);
  document.getElementById('genTotalMotoMeta').textContent = isEn()
    ? `TOTAL HOURS: ${totalMotoH.toFixed(1)} H`
    : `ОБЩИЙ НАКАТ: ${totalMotoH.toFixed(1)} Ч`;
  document.getElementById('oilVolBadge').textContent = cfg.oilVolL.toFixed(2);

  const oilUsed = Number(rato.oilUsedHours ?? totalMotoH);
  const oilRem = Math.max(0, cfg.oilIntervalH - oilUsed);
  const oilPct = Math.min(100, Math.round((oilUsed / Math.max(1, cfg.oilIntervalH)) * 100));
  const oilDue = oilUsed >= cfg.oilIntervalH * 0.9;

  const oilDateRaw = rato.oilResetDate || '';
  const oilDateDisp = (!oilDateRaw || oilDateRaw === 'С начала учёта')
    ? (isEn() ? 'Since tracking start' : 'С начала учёта')
    : oilDateRaw;

  document.getElementById('oilLastDate').textContent = isEn()
    ? `Last reset: ${oilDateDisp}`
    : `Последняя отметка: ${oilDateDisp}`;
  document.getElementById('oilHoursStat').textContent = `${oilUsed.toFixed(1)} / ${cfg.oilIntervalH} ${hUnit}`;
  document.getElementById('oilHoursStat').style.color = oilDue ? 'var(--amber)' : 'var(--cyan)';
  document.getElementById('oilStatusHint').textContent = oilUsed >= cfg.oilIntervalH
    ? (isEn() ? `Oil change recommended (${cfg.oilVolL} L)` : `Неплохо бы при случае поменять масло (${cfg.oilVolL} л)`)
    : (isEn() ? `~${oilRem.toFixed(1)} engine hours until oil change` : `До замены ~${oilRem.toFixed(1)} моточасов`);
  document.getElementById('oilStatusHint').style.color = oilDue ? 'var(--amber)' : 'var(--muted)';
  document.getElementById('oilProgBar').style.width = `${oilPct}%`;
  document.getElementById('oilProgBar').style.background = oilDue ? 'var(--amber)' : 'var(--cyan)';
  document.getElementById('oilMaintCard').classList.toggle('due-warn', oilDue);

  const sparkUsed = Number(rato.sparkUsedHours ?? totalMotoH);
  const sparkRem = Math.max(0, cfg.sparkIntervalH - sparkUsed);
  const sparkPct = Math.min(100, Math.round((sparkUsed / Math.max(1, cfg.sparkIntervalH)) * 100));
  const sparkDue = sparkUsed >= cfg.sparkIntervalH * 0.9;

  const sparkDateRaw = rato.sparkResetDate || '';
  const sparkDateDisp = (!sparkDateRaw || sparkDateRaw === 'Прошлая зима')
    ? (isEn() ? 'Last winter' : 'Прошлая зима')
    : sparkDateRaw;

  document.getElementById('sparkLastDate').textContent = isEn()
    ? `Last reset: ${sparkDateDisp}`
    : `Последняя отметка: ${sparkDateDisp}`;
  document.getElementById('sparkHoursStat').textContent = `${sparkUsed.toFixed(1)} / ${cfg.sparkIntervalH} ${hUnit}`;
  document.getElementById('sparkHoursStat').style.color = sparkDue ? 'var(--amber)' : 'var(--green)';
  document.getElementById('sparkStatusHint').textContent = sparkUsed >= cfg.sparkIntervalH
    ? (isEn() ? `Inspect or replace spark plug when convenient` : `При случае проверь нагар / махни свечу`)
    : (isEn() ? `~${sparkRem.toFixed(1)} h until inspection/replacement` : `До проверки/замены ~${sparkRem.toFixed(1)} ч`);
  document.getElementById('sparkStatusHint').style.color = sparkDue ? 'var(--amber)' : 'var(--muted)';
  document.getElementById('sparkProgBar').style.width = `${sparkPct}%`;
  document.getElementById('sparkProgBar').style.background = sparkDue ? 'var(--amber)' : 'var(--green)';
  document.getElementById('sparkMaintCard').classList.toggle('due-warn', sparkDue);
}
