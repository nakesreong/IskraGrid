// =========================================================================
// GRAFANA PERIOD ANALYTICS & MINUTE-EXACT TIMELINE (12H / 24H / 7D / 30D / CUSTOM)
// =========================================================================

window.selectedPeriod = '12H';

function initDates() {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  const todayIso = `${y}-${m}-${d}`;
  const fEl = document.getElementById('inpDateFrom');
  const tEl = document.getElementById('inpDateTo');
  if (fEl && !fEl.value) fEl.value = todayIso;
  if (tEl && !tEl.value) tEl.value = todayIso;
}

function setPeriod(p) {
  window.selectedPeriod = p;
  const map = { '12H': 'pBtn12h', '24H': 'pBtn24h', '7D': 'pBtn7d', '30D': 'pBtn30d', 'CUSTOM': 'pBtnCustom' };
  Object.keys(map).forEach(k => {
    const el = document.getElementById(map[k]);
    if (el) el.classList.toggle('active', k === p);
  });
  const cRow = document.getElementById('customDateRow');
  if (cRow) cRow.classList.toggle('active', p === 'CUSTOM');
  if (window.S) renderGrafanaPanels(window.S);
}

function getPeriodWindow(nowMs) {
  const p = window.selectedPeriod || '12H';
  if (p === '12H' || p === 'DAY') {
    const sMs = nowMs - 12 * 3600_000;
    return {
      startMs: sMs,
      endMs: nowMs,
      shortTitle: isEn() ? 'SELECTED (12H)' : 'ЗА ПЕРИОД (12Ч)',
      label: isEn()
        ? `LAST 12 HOURS (${fmtTimeHM(sMs)} — ${fmtTimeHM(nowMs)})`
        : `ПОСЛЕДНИЕ 12 ЧАСОВ (${fmtTimeHM(sMs)} — ${fmtTimeHM(nowMs)})`
    };
  }
  if (p === '24H') {
    const sMs = nowMs - 24 * 3600_000;
    return {
      startMs: sMs,
      endMs: nowMs,
      shortTitle: isEn() ? 'SELECTED (24H)' : 'ЗА ПЕРИОД (24Ч)',
      label: isEn()
        ? `LAST 24 HOURS (${fmtDateDM(sMs)} ${fmtTimeHM(sMs)} — ${fmtTimeHM(nowMs)})`
        : `ПОСЛЕДНИЕ 24 ЧАСА (${fmtDateDM(sMs)} ${fmtTimeHM(sMs)} — ${fmtTimeHM(nowMs)})`
    };
  }
  if (p === '7D') {
    return {
      startMs: nowMs - 7 * 86400_000,
      endMs: nowMs,
      shortTitle: isEn() ? 'SELECTED (7D)' : 'ЗА НЕДЕЛЮ (7Д)',
      label: isEn() ? 'LAST 7 DAYS' : 'ПОСЛЕДНИЕ 7 ДНЕЙ'
    };
  }
  if (p === '30D') {
    return {
      startMs: nowMs - 30 * 86400_000,
      endMs: nowMs,
      shortTitle: isEn() ? 'SELECTED (30D)' : 'ЗА МЕСЯЦ (30Д)',
      label: isEn() ? 'LAST 30 DAYS' : 'ПОСЛЕДНИЕ 30 ДНЕЙ'
    };
  }
  initDates();
  const fVal = document.getElementById('inpDateFrom').value;
  const tVal = document.getElementById('inpDateTo').value;
  const sDate = fVal ? new Date(fVal + 'T00:00:00') : new Date(nowMs - 86400_000);
  const eDate = tVal ? new Date(tVal + 'T23:59:59') : new Date(nowMs);
  const sMs = sDate.getTime();
  const eMs = Math.min(nowMs, eDate.getTime());
  return {
    startMs: Math.min(sMs, eMs),
    endMs: Math.max(sMs, eMs),
    shortTitle: isEn() ? 'CUSTOM RANGE' : 'ЗА ВЫБРАННЫЙ ПЕРИОД',
    label: isEn() ? `RANGE: ${fVal} — ${tVal}` : `ПЕРИОД: ${fVal} — ${tVal}`
  };
}

function calcCumulativeFuelAt(tMs, clipped, cfgG) {
  let cumL = 0;
  for (let i = 0; i < clipped.length; i++) {
    const iv = clipped[i];
    if (iv.mode !== 'GENERATOR') continue;
    if (iv.startMs >= tMs) continue;
    const segEnd = Math.min(iv.endMs, tMs);
    if (segEnd > iv.startMs) {
      cumL += calcIntervalFuelLiters(segEnd - iv.startMs, iv.avgInW || iv.avgOutW, cfgG);
    }
  }
  return cumL;
}

function isGenActiveAt(tMs, ptMode, clipped) {
  if (ptMode === 'GENERATOR') return true;
  if (ptMode && ptMode !== 'GENERATOR') return false;
  for (let i = 0; i < clipped.length; i++) {
    const iv = clipped[i];
    if (iv.mode === 'GENERATOR' && tMs >= iv.startMs && tMs <= iv.endMs) {
      return true;
    }
  }
  return false;
}

function buildUnifiedCurvePoints(clipped, rawCurve, spanStart, endMs, d) {
  const cfgG = getGenConfig(d);
  const rawInWin = (rawCurve || [])
    .filter(p => p && p.ts && p.ts >= spanStart && p.ts <= endMs)
    .sort((a, b) => a.ts - b.ts);

  const hasNearRaw = (tMs, tolMs) => {
    for (let i = 0; i < rawInWin.length; i++) {
      const diff = rawInWin[i].ts - tMs;
      if (Math.abs(diff) <= tolMs) return true;
      if (diff > tolMs) break;
    }
    return false;
  };

  const synth = [];
  const span = Math.max(60_000, endMs - spanStart);
  const stepMs = Math.max(180_000, Math.round(span / 96));
  const tolMs = Math.max(210_000, Math.round(stepMs * 0.75));

  clipped.forEach(iv => {
    const s = iv.startMs;
    const e = iv.endMs;
    const dur = Math.max(1, e - s);
    const sSoc = iv.startSoc ?? 50;
    const eSoc = iv.endSoc ?? sSoc;
    const m = iv.mode || 'BATTERY';
    const outW = m === 'BLACKOUT_ZERO' ? 0 : (iv.avgOutW > 0 ? iv.avgOutW : 380);
    const inW = (m === 'GRID' || m === 'GENERATOR') ? (iv.avgInW > 0 ? iv.avgInW : outW) : 0;
    const acInW = (m === 'GRID' || m === 'GENERATOR') ? Math.max(0, inW - outW) : 0;

    const makePt = (tMs, frac) => ({
      t: fmtTimeHM(tMs),
      ts: tMs,
      batt: Math.max(0, Math.min(100, Math.round(sSoc + (eSoc - sSoc) * frac))),
      inW: inW,
      acInW: acInW,
      outW: outW,
      mode: m
    });

    if (!hasNearRaw(s, 90_000)) {
      synth.push(makePt(s + 1000, 0));
    }
    for (let tMs = s + stepMs; tMs < e - stepMs / 2; tMs += stepMs) {
      if (!hasNearRaw(tMs, tolMs)) {
        synth.push(makePt(tMs, (tMs - s) / dur));
      }
    }
    if (!hasNearRaw(e, 90_000)) {
      synth.push(makePt(Math.max(s + 2000, e - 1000), 1));
    }
  });

  const combined = [...synth, ...rawInWin].sort((a, b) => a.ts - b.ts);
  if (combined.length === 0) {
    combined.push({
      t: fmtTimeHM(endMs),
      ts: endMs,
      batt: (d.oukitel && d.oukitel.batteryPct) || 0,
      inW: (d.oukitel && d.oukitel.totalInputW) || 0,
      acInW: (d.oukitel && d.oukitel.acInputW) || 0,
      outW: (d.oukitel && d.oukitel.totalOutputW) || 0,
      mode: d.currentMode || 'BATTERY'
    });
  }

  // Enrich every point with instantaneous fuel burn rate (L/h) and cumulative fuel burned in window (L)
  combined.forEach(p => {
    const genOn = isGenActiveAt(p.ts, p.mode, clipped);
    if (genOn) {
      const w = Math.max(p.inW || 0, p.outW || 0, 40) > 40 ? Math.max(p.inW || 0, p.outW || 0) : 880;
      const kw = Math.min(cfgG.maxKw, w / 1000.0);
      p.fuelRateLh = cfgG.idleLh + kw * cfgG.slopePerKw;
    } else {
      p.fuelRateLh = 0;
    }
    p.fuelCumL = calcCumulativeFuelAt(p.ts, clipped, cfgG);
  });

  return combined;
}

function renderGrafanaPanels(d) {
  if (!d) return;
  const nowMs = d.timestamp || Date.now();
  const win = getPeriodWindow(nowMs);
  document.getElementById('periodRangeLabel').textContent = win.label;

  const hist = d.history || {};
  const rawIntervals = hist.intervals || [];
  const rawCurve = hist.curve || [];

  let gridMs = 0, genMs = 0, battMs = 0, zeroMs = 0;
  const clipped = [];

  rawIntervals.forEach(iv => {
    const origS = iv.startMs || 0;
    const origE = Math.min(nowMs, iv.endMs || nowMs);
    const s = Math.max(win.startMs, origS);
    const e = Math.min(win.endMs, origE);
    if (e > s) {
      const origDur = Math.max(1, origE - origS);
      const origStartSoc = iv.startSoc ?? 0;
      const origEndSoc = iv.endSoc ?? origStartSoc;
      const clipStartSoc = Math.round(origStartSoc + (origEndSoc - origStartSoc) * ((s - origS) / origDur));
      const clipEndSoc = Math.round(origStartSoc + (origEndSoc - origStartSoc) * ((e - origS) / origDur));

      const dur = e - s;
      const m = iv.mode || 'BATTERY';
      if (m === 'GRID') gridMs += dur;
      else if (m === 'GENERATOR') genMs += dur;
      else if (m === 'BLACKOUT_ZERO') zeroMs += dur;
      else battMs += dur;

      clipped.push({
        mode: m,
        startMs: s,
        endMs: e,
        durMs: dur,
        startSoc: clipStartSoc,
        endSoc: clipEndSoc,
        avgInW: iv.avgInW ?? 0,
        avgOutW: iv.avgOutW ?? 0
      });
    }
  });

  const totalTrackedMs = Math.max(1, gridMs + genMs + battMs + zeroMs);
  const pct = ms => Math.round((ms / totalTrackedMs) * 100);

  document.getElementById('sumGridTime').textContent = fmtMinExact(gridMs);
  document.getElementById('sumGridPct').textContent  = isEn()
    ? `${pct(gridMs)}% of tracked time`
    : `${pct(gridMs)}% учтённого времени`;

  document.getElementById('sumGenTime').textContent  = fmtMinExact(genMs);
  const cfgG = getGenConfig(d);
  let exactGenLiters = 0;
  let periodGenRuns = 0;
  let periodGenWeightedW = 0;
  clipped.forEach(iv => {
    if (iv.mode === 'GENERATOR') {
      const w = (iv.avgInW && iv.avgInW > 40) ? iv.avgInW : ((iv.avgOutW && iv.avgOutW > 40) ? iv.avgOutW : 880);
      exactGenLiters += calcIntervalFuelLiters(iv.durMs, w, cfgG);
      periodGenRuns += 1;
      periodGenWeightedW += iv.durMs * w;
    }
  });
  document.getElementById('sumGenPct').textContent   = isEn()
    ? `${pct(genMs)}% • ~${exactGenLiters.toFixed(2)} L gasoline`
    : `${pct(genMs)}% • ~${exactGenLiters.toFixed(2)} л бензина`;

  document.getElementById('sumBattTime').textContent = fmtMinExact(battMs);
  document.getElementById('sumBattPct').textContent  = isEn()
    ? `${pct(battMs)}% (Station battery)`
    : `${pct(battMs)}% (Автономно на станции)`;

  document.getElementById('sumZeroTime').textContent = fmtMinExact(zeroMs);
  document.getElementById('sumZeroPct').textContent  = isEn()
    ? `${pct(zeroMs)}% (Total 0% / Offline)`
    : `${pct(zeroMs)}% (Полный 0 / Оффлайн)`;

  const spanStart = clipped.length > 0 ? Math.max(win.startMs, clipped[0].startMs) : win.startMs;
  const spanEnd = win.endMs;

  drawStateTimelineCanvas('cvStateTimeline', clipped, spanStart, spanEnd);
  renderDailyBreakdown('dailyBreakdownBox', clipped, window.selectedPeriod !== '12H' && window.selectedPeriod !== '24H');
  renderIntervalLog('intervalLogList', clipped);

  const pts = buildUnifiedCurvePoints(clipped, rawCurve, spanStart, spanEnd, d);
  document.getElementById('gfSamplesCount').textContent = isEn()
    ? `${pts.length} pts`
    : `${pts.length} точек`;

  const wUnit = isEn() ? 'W' : 'Вт';
  drawGrafanaChart(
    'cvPower',
    'lgPower',
    pts,
    [
      { key: 'inW',   name: isEn() ? 'Total Input (Grid / Gen)' : 'Вход всего (Сеть / Гена)', unit: wUnit, color: '#73BF69' },
      { key: 'acInW', name: isEn() ? 'Battery Charge'           : 'Заряд в батарею',          unit: wUnit, color: '#5794F2' },
      { key: 'outW',  name: isEn() ? 'Home Load (Output)'       : 'Нагрузка дома (Выход)',    unit: wUnit, color: '#FF9830' }
    ],
    0,
    1800,
    spanStart,
    spanEnd
  );

  drawGrafanaChart(
    'cvSoc',
    'lgSoc',
    pts,
    [
      { key: 'batt', name: isEn() ? 'Oukitel Battery SoC' : 'Заряд батареи Oukitel', unit: '%', color: '#33B5E5' }
    ],
    0,
    100,
    spanStart,
    spanEnd
  );

  // Render Fuel Analytics & Chart on Tab 2
  renderFuelAnalyticsPanel(d, cfgG, rawIntervals, pts, win, spanStart, spanEnd, genMs, exactGenLiters, periodGenRuns, periodGenWeightedW);
}

function renderFuelAnalyticsPanel(d, cfg, rawIntervals, pts, win, spanStart, spanEnd, periodGenMs, periodGenL, periodGenRuns, periodGenWeightedW) {
  const fMeta = document.getElementById('genFuelFormulaMeta');
  if (fMeta) {
    fMeta.textContent = isEn()
      ? `Idle ${cfg.idleLh.toFixed(2)} L/h • ½ ${cfg.fuelHalfLh.toFixed(2)} L/h • Max ${cfg.fuelFullLh.toFixed(2)} L/h`
      : `Холостой ${cfg.idleLh.toFixed(2)} л/ч • ½ ${cfg.fuelHalfLh.toFixed(2)} л/ч • Макс ${cfg.fuelFullLh.toFixed(2)} л/ч`;
  }

  const nowMs = d.timestamp || Date.now();
  const dt = new Date(nowMs);
  const todayStartMs = new Date(dt.getFullYear(), dt.getMonth(), dt.getDate(), 0, 0, 0, 0).getTime();
  const yestStartMs = todayStartMs - 86400_000;
  const d7StartMs = todayStartMs - 6 * 86400_000;
  const d30StartMs = todayStartMs - 29 * 86400_000;

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
  const lhUnit = isEn() ? 'L/h' : 'л/ч';
  const hUnit = isEn() ? 'h' : 'ч';

  // Card 1: Selected Period Fuel
  const pTitleEl = document.getElementById('gfPeriodTitle');
  if (pTitleEl) pTitleEl.textContent = win.shortTitle;
  const pTimeEl = document.getElementById('gfPeriodTime');
  if (pTimeEl) pTimeEl.textContent = fmtMinExact(periodGenMs);
  const pLitEl = document.getElementById('gfPeriodLiters');
  if (pLitEl) pLitEl.textContent = `${periodGenL.toFixed(2)} ${lUnit}`;
  const pSubEl = document.getElementById('gfPeriodSub');
  if (pSubEl) {
    const pAvgW = periodGenMs > 0 ? Math.round(periodGenWeightedW / periodGenMs) : 0;
    const pAvgLh = periodGenMs > 0 ? (periodGenL / (periodGenMs / 3600_000)) : 0;
    pSubEl.textContent = periodGenMs > 0
      ? (isEn() ? `${periodGenRuns} run(s) • ~${pAvgW}W (${pAvgLh.toFixed(2)} L/h)` : `${periodGenRuns} пуск(а) • ~${pAvgW}Вт (${pAvgLh.toFixed(2)} л/ч)`)
      : (isEn() ? 'No generator runs in period' : 'Без запусков за период');
  }

  // Card 2: Today & Yesterday
  const todayAvgW = todayMs > 0 ? Math.round(todayWeightedW / todayMs) : 0;
  const tTimeEl = document.getElementById('gfTodayTime');
  if (tTimeEl) tTimeEl.textContent = fmtMinExact(todayMs);
  const tLitEl = document.getElementById('gfTodayLiters');
  if (tLitEl) tLitEl.textContent = `${todayL.toFixed(2)} ${lUnit}`;
  const tSubEl = document.getElementById('gfTodaySub');
  if (tSubEl) {
    tSubEl.textContent = isEn()
      ? `Yesterday: ${yestL.toFixed(2)} L (${fmtMinExact(yestMs)})`
      : `Вчера: ${yestL.toFixed(2)} л (${fmtMinExact(yestMs)})`;
  }

  // Card 3: 7 Days
  const d7TimeEl = document.getElementById('gf7dTime');
  if (d7TimeEl) d7TimeEl.textContent = fmtMinExact(d7Ms);
  const d7LitEl = document.getElementById('gf7dLiters');
  if (d7LitEl) d7LitEl.textContent = `${d7L.toFixed(2)} ${lUnit}`;
  const d7SubEl = document.getElementById('gf7dSub');
  if (d7SubEl) d7SubEl.textContent = isEn() ? 'Past 7 days' : 'За последние 7 суток';

  // Card 4: Since Full Tank Mark (14.5 L)
  const rato = d.rato || {};
  const tankMarkMs = Number(rato.tankFullMarkMs || nowMs);
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
  const remTankL = Math.max(0, cfg.tankL - sinceTankL);
  const d30TimeEl = document.getElementById('gf30dTime');
  if (d30TimeEl) d30TimeEl.textContent = fmtMinExact(sinceTankMs);
  const d30LitEl = document.getElementById('gf30dLiters');
  if (d30LitEl) d30LitEl.textContent = `${sinceTankL.toFixed(2)} ${lUnit}`;
  const d30SubEl = document.getElementById('gf30dSub');
  if (d30SubEl) {
    const tDate = rato.tankFullDate || fmtDateDM(tankMarkMs);
    d30SubEl.textContent = isEn()
      ? `Full (${tDate}) • ~${remTankL.toFixed(1)}/${cfg.tankL} L left`
      : `С ${tDate} • в баке ~${remTankL.toFixed(1)} из ${cfg.tankL} л`;
  }

  // Draw Fuel Chart (#cvFuel) for the selected period [spanStart, spanEnd]
  const maxFuelY = Math.max(2.5, Math.ceil(Math.max(periodGenL, cfg.fuelFullLh) * 2) / 2);
  drawGrafanaChart(
    'cvFuel',
    'lgFuel',
    pts,
    [
      { key: 'fuelRateLh', name: isEn() ? 'Instant Burn Rate'      : 'Текущий расход (л/ч)',  unit: lhUnit, color: '#FF9830', decimals: 2 },
      { key: 'fuelCumL',   name: isEn() ? 'Burned in Period (Cum)' : 'Сожжено за период (л)', unit: lUnit,  color: '#FADE2A', decimals: 2 }
    ],
    0,
    maxFuelY,
    spanStart,
    spanEnd
  );

  // Daily Fuel Log List (filtered to window or showing all recent days)
  const dayKeys = Object.keys(dailyMap).sort().reverse();
  const daysCntEl = document.getElementById('gfDaysCount');
  if (daysCntEl) {
    daysCntEl.textContent = isEn()
      ? `${dayKeys.length} active day(s)`
      : `${dayKeys.length} дн. с запусками`;
  }
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
}

function drawStateTimelineCanvas(canvasId, clipped, spanStart, endMs) {
  const canvas = document.getElementById(canvasId);
  if (!canvas) return;
  const rect = canvas.parentElement.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 2;
  const W = Math.max(300, rect.width);
  const H = Math.max(50, rect.height);
  canvas.width = W * dpr;
  canvas.height = H * dpr;
  const ctx = canvas.getContext('2d');
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, W, H);

  const padL = 8, padR = 8, padT = 6, padB = 18;
  const barW = W - padL - padR;
  const barH = H - padT - padB;

  ctx.fillStyle = '#181B20';
  ctx.fillRect(padL, padT, barW, barH);

  const span = Math.max(60_000, endMs - spanStart);

  const colors = {
    'GRID': '#73BF69',
    'GENERATOR': '#FF9830',
    'BATTERY': '#F2495C',
    'BLACKOUT_ZERO': '#6E737B'
  };

  clipped.forEach(iv => {
    const x1 = padL + Math.max(0, ((iv.startMs - spanStart) / span) * barW);
    const x2 = padL + Math.min(barW, ((iv.endMs - spanStart) / span) * barW);
    const w = Math.max(2, x2 - x1);
    ctx.fillStyle = colors[iv.mode] || '#F2495C';
    ctx.fillRect(x1, padT, w, barH);
  });

  ctx.fillStyle = '#8E9297';
  ctx.font = '9px monospace';
  const ticks = 4;
  for (let i = 0; i <= ticks; i++) {
    const tMs = spanStart + (span / ticks) * i;
    const x = padL + (barW / ticks) * i;
    ctx.textAlign = i === 0 ? 'left' : (i === ticks ? 'right' : 'center');
    const lbl = (endMs - spanStart) > 90000_000 ? `${fmtDateDM(tMs)} ${fmtTimeHM(tMs)}` : fmtTimeHM(tMs);
    ctx.fillText(lbl, x, H - 4);
  }
}

function renderDailyBreakdown(containerId, clipped, show) {
  const box = document.getElementById(containerId);
  if (!box) return;
  box.innerHTML = '';
  if (!show || clipped.length === 0) return;

  const daysMap = {};
  clipped.forEach(iv => {
    const dayKey = fmtDateDM(iv.startMs);
    if (!daysMap[dayKey]) daysMap[dayKey] = { grid: 0, gen: 0, batt: 0, zero: 0 };
    if (iv.mode === 'GRID') daysMap[dayKey].grid += iv.durMs;
    else if (iv.mode === 'GENERATOR') daysMap[dayKey].gen += iv.durMs;
    else if (iv.mode === 'BLACKOUT_ZERO') daysMap[dayKey].zero += iv.durMs;
    else daysMap[dayKey].batt += iv.durMs;
  });

  Object.keys(daysMap).forEach(day => {
    const d = daysMap[day];
    const tot = Math.max(1, d.grid + d.gen + d.batt + d.zero);
    const row = document.createElement('div');
    row.className = 'd-row';
    row.innerHTML = `
      <span style="color:var(--muted);">${day}</span>
      <div class="d-track">
        <div style="width:${(d.grid/tot)*100}%; background:var(--green);"></div>
        <div style="width:${(d.gen/tot)*100}%; background:var(--amber);"></div>
        <div style="width:${(d.batt/tot)*100}%; background:var(--red);"></div>
        <div style="width:${(d.zero/tot)*100}%; background:var(--zero);"></div>
      </div>
      <span style="text-align:right; color:var(--text);">${isEn() ? 'Grid' : 'Свет'} ${fmtMinExact(d.grid)} / ${isEn() ? 'Gen' : 'Гена'} ${fmtMinExact(d.gen)}</span>
    `;
    box.appendChild(row);
  });
}

function renderIntervalLog(listId, clipped) {
  const list = document.getElementById(listId);
  if (!list) return;
  list.innerHTML = '';
  document.getElementById('intervalsCountLabel').textContent = isEn()
    ? `${clipped.length} intervals`
    : `${clipped.length} интервалов`;

  const names = isEn() ? {
    'GRID': 'DTEK CITY GRID',
    'GENERATOR': 'RATO GENERATOR',
    'BATTERY': 'BATTERY BACKUP (BLACKOUT)',
    'BLACKOUT_ZERO': 'TOTAL 0% / OFFLINE'
  } : {
    'GRID': 'ОТ СЕТИ ДТЕК',
    'GENERATOR': 'ОТ ГЕНЕРАТОРА RATO',
    'BATTERY': 'НА СТАНЦИИ (БЕЗ СЕТИ И ГЕНЫ)',
    'BLACKOUT_ZERO': 'ВСЕ СЕЛО В 0% / ОФФЛАЙН'
  };

  const reversed = [...clipped].reverse();
  reversed.forEach(iv => {
    const row = document.createElement('div');
    row.className = `log-row mode-${iv.mode}`;
    const sameDay = fmtDateDM(iv.startMs) === fmtDateDM(iv.endMs);
    const timeRange = sameDay
      ? `${fmtDateDM(iv.startMs)} ${fmtTimeHM(iv.startMs)} — ${fmtTimeHM(iv.endMs)}`
      : `${fmtDateDM(iv.startMs)} ${fmtTimeHM(iv.startMs)} — ${fmtDateDM(iv.endMs)} ${fmtTimeHM(iv.endMs)}`;

    const socInfo = (iv.startSoc || iv.endSoc)
      ? (isEn() ? `SoC: ${iv.startSoc}% → ${iv.endSoc}%` : `Заряд: ${iv.startSoc}% → ${iv.endSoc}%`)
      : '';
    const wInfo = iv.avgInW > 50
      ? (isEn() ? ` • In ~${iv.avgInW}W` : ` • Вход ~${iv.avgInW}Вт`)
      : (iv.avgOutW > 0 ? (isEn() ? ` • Load ~${iv.avgOutW}W` : ` • Дом ~${iv.avgOutW}Вт`) : '');

    row.innerHTML = `
      <div>
        <div style="font-weight:700; color:var(--text);">${names[iv.mode] || iv.mode}</div>
        <div style="color:var(--muted); margin-top:1px;">${timeRange} • ${socInfo}${wInfo}</div>
      </div>
      <div style="font-weight:700; font-size:11px; text-align:right; padding-left:8px;">
        ${fmtMinExact(iv.durMs)}
      </div>
    `;
    list.appendChild(row);
  });
}

function drawGrafanaChart(canvasId, legendId, pts, seriesList, minYDefault, maxYDefault, spanStart, endMs) {
  const canvas = document.getElementById(canvasId);
  if (!canvas) return;
  const rect = canvas.parentElement.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 2;
  const W = Math.max(300, rect.width);
  const H = Math.max(125, rect.height);
  canvas.width = W * dpr;
  canvas.height = H * dpr;
  const ctx = canvas.getContext('2d');
  ctx.scale(dpr, dpr);

  ctx.clearRect(0, 0, W, H);

  const padL = 36, padR = 10, padT = 10, padB = 20;
  const plotW = W - padL - padR;
  const plotH = H - padT - padB;

  const isSmallScale = maxYDefault <= 25;
  let maxVal = maxYDefault;
  pts.forEach(p => {
    seriesList.forEach(s => {
      const v = Number(p[s.key] || 0);
      if (v > maxVal) {
        maxVal = isSmallScale ? (Math.ceil(v * 2) / 2) : (Math.ceil(v / 100) * 100);
      }
    });
  });
  const minVal = minYDefault;

  ctx.strokeStyle = '#1B1E26';
  ctx.lineWidth = 1;
  ctx.fillStyle = '#6E737B';
  ctx.font = '9px monospace';
  ctx.textAlign = 'right';

  const steps = 4;
  for (let i = 0; i <= steps; i++) {
    const y = padT + (plotH / steps) * i;
    const rawV = maxVal - ((maxVal - minVal) / steps) * i;
    const valStr = isSmallScale ? rawV.toFixed(1) : String(Math.round(rawV));
    ctx.beginPath();
    ctx.moveTo(padL, y);
    ctx.lineTo(padL + plotW, y);
    ctx.stroke();
    ctx.fillText(valStr, padL - 5, y + 3);
  }

  const tStart = spanStart || (pts[0] && pts[0].ts) || (Date.now() - 3600_000);
  const tEnd = endMs || (pts[pts.length - 1] && pts[pts.length - 1].ts) || Date.now();
  const span = Math.max(60_000, tEnd - tStart);

  const vSteps = 4;
  for (let i = 0; i <= vSteps; i++) {
    const tMs = tStart + (span / vSteps) * i;
    const x = padL + (plotW / vSteps) * i;
    ctx.beginPath();
    ctx.moveTo(x, padT);
    ctx.lineTo(x, padT + plotH);
    ctx.stroke();
    ctx.textAlign = i === 0 ? 'left' : (i === vSteps ? 'right' : 'center');
    const lbl = span > 90000_000 ? `${fmtDateDM(tMs)} ${fmtTimeHM(tMs)}` : fmtTimeHM(tMs);
    ctx.fillText(lbl, x, H - 5);
  }

  const tbody = document.getElementById(legendId);
  if (tbody) tbody.innerHTML = '';

  seriesList.forEach(s => {
    const vals = pts.map(p => Number(p[s.key] ?? 0));
    const last = vals[vals.length - 1] ?? 0;
    const min = Math.min(...vals);
    const max = Math.max(...vals);
    const mean = vals.reduce((a, b) => a + b, 0) / (vals.length || 1);

    const coords = pts.map((p, idx) => {
      const v = vals[idx];
      const fracX = p.ts ? Math.max(0, Math.min(1, (p.ts - tStart) / span)) : (pts.length > 1 ? idx / (pts.length - 1) : 1);
      const x = padL + fracX * plotW;
      const norm = (v - minVal) / Math.max(0.001, (maxVal - minVal));
      const y = padT + plotH - Math.max(0, Math.min(1, norm)) * plotH;
      return { x, y };
    });

    if (coords.length === 1) {
      coords.unshift({ x: padL, y: coords[0].y });
    }

    ctx.beginPath();
    ctx.moveTo(coords[0].x, padT + plotH);
    coords.forEach(c => ctx.lineTo(c.x, c.y));
    ctx.lineTo(coords[coords.length - 1].x, padT + plotH);
    ctx.closePath();
    ctx.fillStyle = s.color + '1F';
    ctx.fill();

    ctx.beginPath();
    coords.forEach((c, idx) => {
      if (idx === 0) ctx.moveTo(c.x, c.y);
      else ctx.lineTo(c.x, c.y);
    });
    ctx.strokeStyle = s.color;
    ctx.lineWidth = 1.6;
    ctx.stroke();

    if (tbody) {
      const dec = s.decimals ?? 0;
      const fmt = v => (dec > 0 ? v.toFixed(dec) : Math.round(v)) + ' ' + s.unit;
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td><span class="lg-line" style="background:${s.color};"></span>${s.name}</td>
        <td style="font-weight:700;">${fmt(last)}</td>
        <td>${fmt(min)}</td>
        <td>${fmt(max)}</td>
        <td>${fmt(mean)}</td>
      `;
      tbody.appendChild(tr);
    }
  });
}
