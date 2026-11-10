// =========================================================================
// GENERATOR RATO R3000 // FUEL IN LITERS, OIL, SPARK PLUG & PASSPORT
// =========================================================================

let genConfigInitialized = false;

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
  const nowMs = d.timestamp || Date.now();
  const dt = new Date(nowMs);
  const todayStartMs = new Date(dt.getFullYear(), dt.getMonth(), dt.getDate(), 0, 0, 0, 0).getTime();
  const yestStartMs = todayStartMs - 86400_000;
  const d7StartMs = todayStartMs - 6 * 86400_000;
  const d30StartMs = todayStartMs - 29 * 86400_000;

  document.getElementById('genFuelFormulaMeta').textContent = isEn()
    ? `Idle ${cfg.idleLh.toFixed(2)} L/h • ½ ${cfg.fuelHalfLh.toFixed(2)} L/h • Max ${cfg.fuelFullLh.toFixed(2)} L/h`
    : `Холостой ${cfg.idleLh.toFixed(2)} л/ч • ½ ${cfg.fuelHalfLh.toFixed(2)} л/ч • Макс ${cfg.fuelFullLh.toFixed(2)} л/ч`;

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

  const rawIntervals = (d.history && d.history.intervals) ? d.history.intervals : [];
  let todayMs = 0, todayL = 0, todayRuns = 0, todayWeightedW = 0;
  let yestMs = 0, yestL = 0, yestRuns = 0;
  let d7Ms = 0, d7L = 0;
  let d30Ms = 0, d30L = 0;

  const dailyMap = {};

  rawIntervals.forEach(iv => {
    if (iv.mode !== 'GENERATOR') return;
    const s = iv.startMs || 0;
    const e = Math.min(nowMs, iv.endMs || nowMs);
    if (e <= s) return;

    const w = (iv.avgInW && iv.avgInW > 40) ? iv.avgInW : ((iv.avgOutW && iv.avgOutW > 40) ? iv.avgOutW : 880);

    let cur = s;
    while (cur < e) {
      const cDt = new Date(cur);
      const dayStart = new Date(cDt.getFullYear(), cDt.getMonth(), cDt.getDate(), 0, 0, 0, 0).getTime();
      const nextDay = dayStart + 86400_000;
      const segEnd = Math.min(e, nextDay);
      const dur = segEnd - cur;
      const lit = calcIntervalFuelLiters(dur, w, cfg);

      const key = `${cDt.getFullYear()}-${String(cDt.getMonth()+1).padStart(2,'0')}-${String(cDt.getDate()).padStart(2,'0')}`;
      if (!dailyMap[key]) {
        dailyMap[key] = { key, ts: dayStart, label: fmtDateDM(dayStart), durMs: 0, liters: 0, runs: 0, weightedW: 0 };
      }
      dailyMap[key].durMs += dur;
      dailyMap[key].liters += lit;
      dailyMap[key].runs += 1;
      dailyMap[key].weightedW += dur * w;

      if (dayStart === todayStartMs) {
        todayMs += dur;
        todayL += lit;
        todayRuns += 1;
        todayWeightedW += dur * w;
      } else if (dayStart === yestStartMs) {
        yestMs += dur;
        yestL += lit;
        yestRuns += 1;
      }
      if (dayStart >= d7StartMs) {
        d7Ms += dur;
        d7L += lit;
      }
      if (dayStart >= d30StartMs) {
        d30Ms += dur;
        d30L += lit;
      }
      cur = segEnd;
    }
  });

  const lUnit = isEn() ? 'L' : 'л';
  const hUnit = isEn() ? 'h' : 'ч';

  const todayAvgW = todayMs > 0 ? Math.round(todayWeightedW / todayMs) : 0;
  document.getElementById('gfTodayTime').textContent = fmtMinExact(todayMs);
  document.getElementById('gfTodayLiters').textContent = `${todayL.toFixed(2)} ${lUnit}`;
  document.getElementById('gfTodaySub').textContent = todayMs > 0
    ? (isEn() ? `${todayRuns} run(s) • avg ${todayAvgW} W` : `${todayRuns} пуск(а) • ср. ${todayAvgW} Вт`)
    : (isEn() ? 'No generator runs today' : 'Генератор сегодня не запускался');

  document.getElementById('gfYestTime').textContent = fmtMinExact(yestMs);
  document.getElementById('gfYestLiters').textContent = `${yestL.toFixed(2)} ${lUnit}`;
  document.getElementById('gfYestSub').textContent = yestMs > 0
    ? (isEn() ? `${yestRuns} run(s) yesterday` : `${yestRuns} пуск(а) за вчера`)
    : (isEn() ? 'No runs' : 'Без запусков');

  document.getElementById('gf7dTime').textContent = fmtMinExact(d7Ms);
  document.getElementById('gf7dLiters').textContent = `${d7L.toFixed(2)} ${lUnit}`;
  document.getElementById('gf7dSub').textContent = isEn() ? 'Past 7 days' : 'За последние 7 суток';

  const totalMotoH = Number(rato.motoHours || 0);
  const totalEstL = Math.max(d30L, totalMotoH * ((cfg.idleLh + (0.88 * cfg.slopePerKw))));
  document.getElementById('gf30dTime').textContent = `${totalMotoH.toFixed(1)} ${hUnit}`;
  document.getElementById('gf30dLiters').textContent = `${totalEstL.toFixed(1)} ${lUnit}`;
  document.getElementById('gf30dSub').textContent = isEn()
    ? `Total engine hours: ${totalMotoH.toFixed(1)} h`
    : `Общий накат: ${totalMotoH.toFixed(1)} моточасов`;

  const dayKeys = Object.keys(dailyMap).sort().reverse();
  document.getElementById('gfDaysCount').textContent = isEn()
    ? `${dayKeys.length} active day(s)`
    : `${dayKeys.length} дн. с запусками`;
  const listEl = document.getElementById('genDailyFuelList');
  if (listEl) {
    listEl.innerHTML = '';
    if (dayKeys.length === 0) {
      listEl.innerHTML = `<div style="font-family:var(--mono); font-size:10px; color:var(--muted); padding:6px;">${isEn() ? 'No generator runs recorded yet.' : 'Пока нет записанных запусков генератора.'}</div>`;
    } else {
      dayKeys.forEach(k => {
        const item = dailyMap[k];
        const avgW = item.durMs > 0 ? Math.round(item.weightedW / item.durMs) : 880;
        const avgLh = item.durMs > 0 ? (item.liters / (item.durMs / 3600_000)) : cfg.fuelHalfLh;
        const isToday = item.ts === todayStartMs;
        const isYest = item.ts === yestStartMs;
        const tag = isToday
          ? (isEn() ? ' (TODAY)' : ' (СЕГОДНЯ)')
          : (isYest ? (isEn() ? ' (YESTERDAY)' : ' (ВЧЕРА)') : '');

        const row = document.createElement('div');
        row.className = 'log-row mode-GENERATOR';
        row.innerHTML = `
          <div>
            <div style="font-weight:700; color:var(--text);">${item.label}${tag} • ${fmtMinExact(item.durMs)}</div>
            <div style="color:var(--muted); margin-top:1px;">${isEn()
              ? `Runs: ${item.runs} • Avg load ~${avgW} W (${avgLh.toFixed(2)} L/h)`
              : `Пусков: ${item.runs} • Ср. нагрузка ~${avgW} Вт (${avgLh.toFixed(2)} л/ч)`}</div>
          </div>
          <div style="font-weight:700; font-size:13px; color:var(--amber); text-align:right;">
            ${item.liters.toFixed(2)} ${lUnit}
          </div>
        `;
        listEl.appendChild(row);
      });
    }
  }

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
