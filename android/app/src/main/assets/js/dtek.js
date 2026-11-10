// =========================================================================
// DTEK KYIV SCHEDULE & EMERGENCY SHUTDOWN FALLBACK RENDERER
// =========================================================================

function refreshDtekNow() {
  if (window.IskraNative && window.IskraNative.refreshDtek) {
    window.IskraNative.refreshDtek();
  } else if (window.IskraNative) {
    window.IskraNative.forceRefresh();
  }
}

function renderDtekSchedule(d) {
  if (!d) return;
  const sched = d.dtekSchedule;
  if (!sched || !sched.today) return;

  const nowMs = d.timestamp || Date.now();
  const dt = new Date(nowMs);
  const nowMin = dt.getHours() * 60 + dt.getMinutes();
  const localIso = `${dt.getFullYear()}-${String(dt.getMonth()+1).padStart(2,'0')}-${String(dt.getDate()).padStart(2,'0')}`;

  let todayObj = sched.today;
  let tomorrowObj = sched.tomorrow || { slots: [], date: '' };
  if (tomorrowObj.date && tomorrowObj.date.slice(0, 10) === localIso) {
    todayObj = tomorrowObj;
    tomorrowObj = { slots: [], date: '' };
  }

  const isEmergency = (todayObj && todayObj.status === 'EmergencyShutdowns');
  const rawTodaySlots = (todayObj && todayObj.slots) ? todayObj.slots : [];
  const fallbackTodaySlots = (todayObj && todayObj.fallbackSlots) ? todayObj.fallbackSlots : [];
  const todaySlots = rawTodaySlots.length > 0 ? rawTodaySlots : fallbackTodaySlots;

  const rawTomorrowSlots = (tomorrowObj && tomorrowObj.slots) ? tomorrowObj.slots : [];
  const fallbackTomorrowSlots = (tomorrowObj && tomorrowObj.fallbackSlots) ? tomorrowObj.fallbackSlots : [];
  const tomorrowSlots = rawTomorrowSlots.length > 0 ? rawTomorrowSlots : fallbackTomorrowSlots;

  document.getElementById('dtGroupBadge').textContent = sched.group || '33.1';
  document.getElementById('dtAddrBadge').textContent = isEn()
    ? 'Kyiv, UA (DTEK Grid)'
    : (sched.address || 'Kyiv, UA');

  if (sched.fetchedAtMs) {
    document.getElementById('dtUpdatedLabel').textContent = isEn()
      ? `Updated at ${fmtTimeHM(sched.fetchedAtMs)}`
      : `Обновлено в ${fmtTimeHM(sched.fetchedAtMs)}`;
  }

  const tagEl = document.getElementById('dtHeroTag');
  const spanEl = document.getElementById('dtHeroSlotSpan');
  const countEl = document.getElementById('dtHeroCountdown');
  const nextEl = document.getElementById('dtHeroNextInfo');
  const barEl = document.getElementById('dtHeroBar');
  const factEl = document.getElementById('dtFactMatch');
  const gridUp = !!(d.smartLife && d.smartLife.gridOnline);

  let curSlot = todaySlots.find(s => nowMin >= s.start && nowMin < s.end) || todaySlots[todaySlots.length - 1];

  if (isEmergency) {
    tagEl.textContent = isEn()
      ? '⚠️ DTEK: EMERGENCY BLACKOUTS'
      : '⚠️ ДТЕК: ЭКСТРЕННЫЕ ОТКЛЮЧЕНИЯ';
    tagEl.style.color = 'var(--amber)';

    countEl.textContent = isEn()
      ? 'SCHEDULES SUSPENDED (EMERGENCY)'
      : 'ГРАФИКИ НЕ ДЕЙСТВУЮТ (АВАРИЙНЫЙ РЕЖИМ)';
    countEl.style.color = gridUp ? 'var(--green)' : 'var(--amber)';

    if (curSlot) {
      const totalSlotMin = Math.max(1, curSlot.end - curSlot.start);
      const elapsedMin = Math.max(0, nowMin - curSlot.start);
      const pctDone = Math.min(100, Math.round((elapsedMin / totalSlotMin) * 100));
      spanEl.textContent = isEn()
        ? `Baseline: ${minToHM(curSlot.start)} — ${minToHM(curSlot.end)}`
        : `План 33.1: ${minToHM(curSlot.start)} — ${minToHM(curSlot.end)}`;
      barEl.style.width = `${pctDone}%`;
      barEl.style.background = gridUp ? 'var(--green)' : 'var(--amber)';

      const idx = todaySlots.indexOf(curSlot);
      const nextOnSlot = todaySlots.slice(idx).find(s => s.type !== 'Definite' && s.end > nowMin);
      if (nextOnSlot) {
        nextEl.textContent = isEn()
          ? `Baseline slot for group ${sched.group || '33.1'}: power window ${minToHM(nextOnSlot.start)} — ${minToHM(nextOnSlot.end)}`
          : `Ориентир по обычному графику ${sched.group || '33.1'}: окно со светом ${minToHM(nextOnSlot.start)} — ${minToHM(nextOnSlot.end)}`;
        nextEl.style.color = 'var(--muted)';
      } else {
        nextEl.textContent = isEn()
          ? 'Hourly schedules are temporarily inactive by Ukrenergo/DTEK order'
          : 'Почасовые графики временно отменены распоряжением Укрэнерго / ДТЕК';
        nextEl.style.color = 'var(--muted)';
      }
    } else {
      spanEl.textContent = isEn() ? 'Ukrenergo / DTEK' : 'Укрэнерго / ДТЕК';
      barEl.style.width = '100%';
      barEl.style.background = gridUp ? 'var(--green)' : 'var(--amber)';
      nextEl.textContent = isEn()
        ? 'Hourly schedules are temporarily inactive during emergency shutdowns'
        : 'Во время экстренных отключений плановые слоты не действуют';
      nextEl.style.color = 'var(--muted)';
    }

    factEl.textContent = gridUp
      ? (isEn() ? '⚡ Live socket_1: POWER IS ON right now' : '⚡ Факт по socket_1: сейчас СВЕТ ЕСТЬ!')
      : (isEn() ? '🔴 Live socket_1: NO POWER (emergency outage)' : '🔴 Факт по socket_1: сейчас СВЕТА НЕТ (экстренное)');
    factEl.style.color = gridUp ? 'var(--green)' : 'var(--amber)';
  } else if (curSlot) {
    const isOff = curSlot.type === 'Definite';
    let effectiveEndMin = curSlot.end;
    let effectiveTotalMin = curSlot.end - curSlot.start;
    if (curSlot.end >= 1440 && tomorrowSlots.length > 0 && tomorrowSlots[0].type === curSlot.type) {
      effectiveEndMin = 1440 + tomorrowSlots[0].end;
      effectiveTotalMin = effectiveEndMin - curSlot.start;
    }

    const remMin = Math.max(0, effectiveEndMin - nowMin);
    const elapsedMin = Math.max(0, nowMin - curSlot.start);
    const pctDone = Math.min(100, Math.round((elapsedMin / Math.max(1, effectiveTotalMin)) * 100));

    spanEl.textContent = `${minToHM(curSlot.start)} — ${minToHM(effectiveEndMin)} (${fmtDurMin(effectiveTotalMin)})`;
    barEl.style.width = `${pctDone}%`;

    if (isOff) {
      tagEl.textContent = isEn() ? '🔴 SCHEDULED: OUTAGE' : '🔴 ПО ГРАФИКУ: ОТКЛЮЧЕНИЕ';
      tagEl.style.color = 'var(--red)';
      barEl.style.background = 'var(--red)';
      countEl.textContent = isEn()
        ? `POWER ON AT ${minToHM(effectiveEndMin)} (in ${fmtDurMin(remMin)})`
        : `СВЕТ ДАДУТ В ${minToHM(effectiveEndMin)} (через ${fmtDurMin(remMin)})`;
      countEl.style.color = 'var(--text)';

      const idx = todaySlots.indexOf(curSlot);
      let nextOn = todaySlots.slice(idx + 1).find(s => s.type !== 'Definite');
      if (!nextOn && tomorrowSlots.length > 0) {
        nextOn = tomorrowSlots.find(s => s.type !== 'Definite');
      }
      if (nextOn) {
        const durOn = nextOn.end - nextOn.start;
        nextEl.textContent = isEn()
          ? `Next power window: ${minToHM(nextOn.start)} — ${minToHM(nextOn.end)} (${fmtDurMin(durOn)})`
          : `Следующее окно со светом: ${minToHM(nextOn.start)} — ${minToHM(nextOn.end)} (${fmtDurMin(durOn)})`;
        nextEl.style.color = 'var(--green)';
      } else {
        nextEl.textContent = isEn() ? 'Awaiting tomorrow schedule' : 'Ожидание графика на завтра';
      }

      if (gridUp) {
        factEl.textContent = isEn()
          ? '⚡ Live socket_1: POWER IS STILL ON (DTEK delayed outage!)'
          : '⚡ Сверка с socket_1: СВЕТ ЕЩЁ ЕСТЬ (ДТЕК пока не выключил!)';
        factEl.style.color = 'var(--green)';
      } else {
        factEl.textContent = isEn()
          ? 'Live socket_1: power off (matches DTEK schedule)'
          : 'Сверка с socket_1: света нет (совпадает с графиком)';
        factEl.style.color = 'var(--muted)';
      }
    } else {
      tagEl.textContent = isEn() ? '🟢 SCHEDULED: POWER ON' : '🟢 ПО ГРАФИКУ: СВЕТ ЕСТЬ';
      tagEl.style.color = 'var(--green)';
      barEl.style.background = 'var(--green)';
      countEl.textContent = isEn()
        ? `OUTAGE AT ${minToHM(effectiveEndMin)} (in ${fmtDurMin(remMin)})`
        : `ОТКЛЮЧЕНИЕ В ${minToHM(effectiveEndMin)} (через ${fmtDurMin(remMin)})`;
      countEl.style.color = 'var(--green)';

      const idx = todaySlots.indexOf(curSlot);
      let nextOff = todaySlots.slice(idx + 1).find(s => s.type === 'Definite');
      let nextOffEnd = nextOff ? nextOff.end : 0;
      if (nextOff && nextOff.end >= 1440 && tomorrowSlots.length > 0 && tomorrowSlots[0].type === 'Definite') {
        nextOffEnd = 1440 + tomorrowSlots[0].end;
      }
      if (nextOff) {
        const durOff = nextOffEnd - nextOff.start;
        nextEl.textContent = isEn()
          ? `Upcoming blackout: ${minToHM(nextOff.start)} — ${minToHM(nextOffEnd)} (${fmtDurMin(durOff)})`
          : `Следующее отключение: ${minToHM(nextOff.start)} — ${minToHM(nextOffEnd)} (${fmtDurMin(durOff)})`;
        nextEl.style.color = 'var(--amber)';
      }

      if (gridUp) {
        factEl.textContent = isEn()
          ? 'Live socket_1: power is on (matches DTEK schedule)'
          : 'Сверка с socket_1: свет есть (совпадает с графиком)';
        factEl.style.color = 'var(--green)';
      } else {
        factEl.textContent = isEn()
          ? '⚠️ Live socket_1: NO POWER (emergency or delayed switch-on!)'
          : '⚠️ Сверка с socket_1: СВЕТА НЕТ (задержка включения / авария!)';
        factEl.style.color = 'var(--red)';
      }
    }
  }

  const todayDateStr = todayObj.date ? fmtDateDM(new Date(todayObj.date).getTime()) : fmtDateDM(nowMs);
  document.getElementById('dtTodayTitle').textContent = isEn()
    ? `TODAY (${todayDateStr}) // HOURLY TIMETABLE`
    : `СЕГОДНЯ (${todayDateStr}) // ГРАФИК ПО ЧАСАМ`;
  renderDtekDayBlock(rawTodaySlots, tomorrowSlots, 'dtTodaySummary', 'dtTodayTrack', 'dtTodayList', nowMin, todayObj.status, fallbackTodaySlots);

  const tomDateStr = tomorrowObj.date ? fmtDateDM(new Date(tomorrowObj.date).getTime()) : fmtDateDM(nowMs + 86400_000);
  document.getElementById('dtTomorrowTitle').textContent = isEn()
    ? `TOMORROW (${tomDateStr}) // HOURLY TIMETABLE`
    : `ЗАВТРА (${tomDateStr}) // ГРАФИК ПО ЧАСАМ`;
  renderDtekDayBlock(rawTomorrowSlots, [], 'dtTomorrowSummary', 'dtTomorrowTrack', 'dtTomorrowList', -1, tomorrowObj.status, fallbackTomorrowSlots);
}

function renderDtekDayBlock(rawSlots, nextDaySlots, sumId, trackId, listId, nowMin, status, fallbackSlots) {
  const sumEl = document.getElementById(sumId);
  const trackEl = document.getElementById(trackId);
  const listEl = document.getElementById(listId);
  if (!trackEl || !listEl) return;

  trackEl.innerHTML = '';
  listEl.innerHTML = '';

  const isEmergency = (status === 'EmergencyShutdowns');
  const usingFallback = (!rawSlots || rawSlots.length === 0) && (fallbackSlots && fallbackSlots.length > 0);
  const slots = (rawSlots && rawSlots.length > 0) ? rawSlots : (fallbackSlots || []);

  if (!slots || slots.length === 0) {
    if (isEmergency) {
      sumEl.textContent = isEn() ? '⚠️ Emergency Shutdowns' : '⚠️ Экстренные отключения';
      sumEl.style.color = 'var(--amber)';
      listEl.innerHTML = `<div style="font-family:var(--mono); font-size:10px; color:var(--amber); padding:7px; background:rgba(255,152,48,0.08); border:1px solid rgba(255,152,48,0.3); border-radius:4px;">${
        isEn()
          ? '⚠️ DTEK Emergency Shutdowns are in effect. Hourly schedules for this day have not been published.'
          : '⚠️ Действуют экстренные отключения ДТЕК. Почасовой график на этот день не опубликован.'
      }</div>`;
    } else {
      sumEl.textContent = isEn() ? 'Awaiting DTEK publication' : 'Ожидание публикации ДТЕК';
      sumEl.style.color = '';
      listEl.innerHTML = `<div style="font-family:var(--mono); font-size:10px; color:var(--muted); padding:6px;">${isEn() ? 'Schedule for this day has not been published by DTEK yet.' : 'График на этот день ещё не опубликован ДТЕК.'}</div>`;
    }
    return;
  }

  if (isEmergency && usingFallback) {
    const warnBanner = document.createElement('div');
    warnBanner.style.cssText = 'font-family:var(--mono); font-size:10px; color:var(--amber); padding:6px 8px; margin-bottom:6px; background:rgba(255,152,48,0.09); border:1px solid rgba(255,152,48,0.32); border-radius:4px;';
    warnBanner.textContent = isEn()
      ? '⚠️ Emergency shutdowns active — official timetable suspended. Showing saved baseline schedule for reference:'
      : '⚠️ Введены экстренные отключения — почасовой график официально не действует. Ниже сохранённый плановый график группы для ориентира:';
    listEl.appendChild(warnBanner);
  }

  let onMin = 0, offMin = 0;
  slots.forEach(s => {
    const dur = Math.max(0, (s.end || 0) - (s.start || 0));
    if (s.type === 'Definite') offMin += dur;
    else onMin += dur;

    const seg = document.createElement('div');
    seg.style.width = `${(dur / 1440) * 100}%`;
    seg.style.height = '100%';
    seg.style.background = s.type === 'Definite' ? 'rgba(242, 73, 92, 0.78)' : 'rgba(115, 191, 105, 0.78)';
    seg.style.borderRight = '1px solid #0A0C10';
    trackEl.appendChild(seg);
  });

  if (nowMin >= 0 && nowMin <= 1440) {
    const marker = document.createElement('div');
    marker.className = 'dtek-now-line';
    marker.style.left = `${(nowMin / 1440) * 100}%`;
    trackEl.appendChild(marker);
  }

  sumEl.style.color = isEmergency ? 'var(--amber)' : '';
  sumEl.textContent = isEn()
    ? `${isEmergency ? '⚠️ Plan: ' : ''}🟢 On: ${fmtDurMin(onMin)} • 🔴 Off: ${fmtDurMin(offMin)}`
    : `${isEmergency ? '⚠️ План: ' : ''}🟢 Свет: ${fmtDurMin(onMin)} • 🔴 Откл: ${fmtDurMin(offMin)}`;

  slots.forEach((s, idx) => {
    const isOff = s.type === 'Definite';
    const isNow = nowMin >= s.start && nowMin < s.end;
    const dur = Math.max(0, s.end - s.start);

    let extraNote = '';
    if (idx === slots.length - 1 && s.end >= 1440 && nextDaySlots && nextDaySlots.length > 0 && nextDaySlots[0].type === s.type) {
      const stitchedDur = dur + nextDaySlots[0].end;
      extraNote = isEn()
        ? ` <span style="color:var(--amber);">→ continues until ${minToHM(nextDaySlots[0].end)} (total ${fmtDurMin(stitchedDur)})</span>`
        : ` <span style="color:var(--amber);">→ до ${minToHM(nextDaySlots[0].end)} ночи (всего ${fmtDurMin(stitchedDur)})</span>`;
    }

    const planSuffix = (isEmergency && usingFallback) ? (isEn() ? ' (BASELINE)' : ' (ПО ПЛАНУ)') : '';
    const row = document.createElement('div');
    row.className = 'dtek-slot ' + (isOff ? 'slot-off' : 'slot-on') + (isNow ? ' slot-now' : '');
    row.innerHTML = `
      <div>
        <div style="font-weight:700; color:${isOff ? 'var(--red)' : 'var(--green)'};">
          ${isOff ? (isEn() ? '🔴 OUTAGE' : '🔴 ОТКЛЮЧЕНИЕ') : (isEn() ? '🟢 POWER ON' : '🟢 СВЕТ ЕСТЬ')}${planSuffix}
          ${isNow ? `<span style="background:var(--cyan); color:#000; padding:1px 5px; font-size:9px; margin-left:6px; border-radius:2px;">${isEn() ? 'NOW' : 'СЕЙЧАС'}</span>` : ''}
        </div>
        <div style="color:var(--text); margin-top:2px;">
          <b>${minToHM(s.start)} — ${minToHM(s.end)}</b>${extraNote}
        </div>
      </div>
      <div style="font-weight:700; font-size:12px; color:${isOff ? 'var(--red)' : 'var(--green)'}; text-align:right;">
        ${fmtDurMin(dur)}
      </div>
    `;
    listEl.appendChild(row);
  });
}
