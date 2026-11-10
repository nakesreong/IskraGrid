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
      label: isEn()
        ? `LAST 24 HOURS (${fmtDateDM(sMs)} ${fmtTimeHM(sMs)} — ${fmtTimeHM(nowMs)})`
        : `ПОСЛЕДНИЕ 24 ЧАСА (${fmtDateDM(sMs)} ${fmtTimeHM(sMs)} — ${fmtTimeHM(nowMs)})`
    };
  }
  if (p === '7D') {
    return { startMs: nowMs - 7 * 86400_000, endMs: nowMs, label: isEn() ? 'LAST 7 DAYS' : 'ПОСЛЕДНИЕ 7 ДНЕЙ' };
  }
  if (p === '30D') {
    return { startMs: nowMs - 30 * 86400_000, endMs: nowMs, label: isEn() ? 'LAST 30 DAYS' : 'ПОСЛЕДНИЕ 30 ДНЕЙ' };
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
    label: isEn() ? `RANGE: ${fVal} — ${tVal}` : `ПЕРИОД: ${fVal} — ${tVal}`
  };
}

function buildUnifiedCurvePoints(clipped, rawCurve, spanStart, endMs, d) {
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
      outW: (d.oukitel && d.oukitel.totalOutputW) || 0
    });
  }
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
  clipped.forEach(iv => {
    if (iv.mode === 'GENERATOR') {
      exactGenLiters += calcIntervalFuelLiters(iv.durMs, iv.avgInW || iv.avgOutW, cfgG);
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

  let maxVal = maxYDefault;
  pts.forEach(p => {
    seriesList.forEach(s => {
      const v = Number(p[s.key] || 0);
      if (v > maxVal) maxVal = Math.ceil(v / 100) * 100;
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
    const val = Math.round(maxVal - ((maxVal - minVal) / steps) * i);
    ctx.beginPath();
    ctx.moveTo(padL, y);
    ctx.lineTo(padL + plotW, y);
    ctx.stroke();
    ctx.fillText(String(val), padL - 5, y + 3);
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
      const norm = (v - minVal) / Math.max(1, (maxVal - minVal));
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
      const fmt = v => Math.round(v) + ' ' + s.unit;
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
