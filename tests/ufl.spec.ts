import { test, expect, Page } from '@playwright/test';

const APP_PATH = '/ufl/ufl-mobile.html';
const ANDROID_APP_PATH = '/ufl-android/index.html';

const parseTimer = (s: string): number => {
  const [m, sec] = s.split(':').map(Number);
  return m * 60 + sec;
};

const startMatch = async (page: Page) => {
  await page.locator('#name-l').fill('LEFT');
  await page.locator('#name-r').fill('RIGHT');
  await page.locator('#btn-start').tap();
  await expect(page.locator('#s-match.active')).toBeVisible({ timeout: 5000 });
};

test.describe('UFL fencing app', () => {
  test('loads without console errors and setup screen is interactive', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));
    page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });

    await page.goto(APP_PATH);
    await expect(page.locator('#s-setup.active')).toBeVisible();
    await expect(page.locator('#btn-import-video')).toBeVisible();

    await page.locator('[data-group="wpn"][data-val="epee"]').tap();
    await expect(page.locator('[data-group="wpn"][data-val="epee"]')).toHaveClass(/active/);

    await page.locator('[data-group="per"][data-val="3"]').tap();
    await expect(page.locator('[data-group="per"][data-val="3"]')).toHaveClass(/active/);

    expect(errors, errors.join('\n')).toEqual([]);
  });

  test('timer resumes after returning to match from events list (P0 #2)', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);

    const before = parseTimer(await page.locator('#timer-disp').innerText());

    await page.locator('#pause-btn').tap();
    await expect(page.locator('#s-result.active')).toBeVisible();

    await page.locator('#res-events').tap();
    await expect(page.locator('#s-events.active')).toBeVisible();

    await page.locator('#ev-back').tap();
    await expect(page.locator('#s-match.active')).toBeVisible();

    await page.waitForTimeout(2500);
    const after = parseTimer(await page.locator('#timer-disp').innerText());

    expect(after, `timer should have decreased; before=${before}, after=${after}`).toBeLessThan(before);
  });

  test('END MATCH reveals NEW MATCH which resets back to setup (P0 #3)', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);

    await page.locator('#pause-btn').tap();
    await expect(page.locator('#s-result.active')).toBeVisible();

    await expect(page.locator('#res-new')).toBeHidden();
    await expect(page.locator('#res-end')).toBeVisible();

    await page.locator('#res-end').tap();

    await expect(page.locator('#res-new')).toBeVisible();
    await expect(page.locator('#res-end')).toBeHidden();
    await expect(page.locator('#res-resume')).toBeHidden();

    await page.locator('#res-new').tap();
    await expect(page.locator('#s-setup.active')).toBeVisible();
  });
});

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

// Record one left-side hit deterministically via the app's confirm path.
const recordHit = (page: Page) => page.evaluate(() => (window as any).doConfirm('L', 100, true));

test.describe('UX-1 persistence', () => {
  test('match start writes a UUID-keyed index entry and snapshot', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);
    await page.waitForTimeout(400);

    const idx = await page.evaluate(() => JSON.parse(localStorage.getItem('ufl:index') || '[]'));
    expect(idx).toHaveLength(1);
    expect(idx[0].id).toMatch(UUID_RE);
    expect(idx[0].finished).toBe(false);

    const client = await page.evaluate(() => localStorage.getItem('ufl:clientId'));
    expect(client).toMatch(UUID_RE);

    const snap = await page.evaluate(id => JSON.parse(localStorage.getItem('ufl:match:' + id) || 'null'), idx[0].id);
    expect(snap.matchId).toBe(idx[0].id);
    expect(snap.clientId).toBe(client);
  });

  test('recorded events persist with piste:null', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);
    await recordHit(page);
    await page.waitForTimeout(400);

    const snap = await page.evaluate(() => {
      const id = JSON.parse(localStorage.getItem('ufl:index') || '[]')[0].id;
      return JSON.parse(localStorage.getItem('ufl:match:' + id) || 'null');
    });
    expect(snap.scoreL).toBe(1);
    expect(snap.events.length).toBeGreaterThanOrEqual(1);
    expect(snap.events.every((e: any) => 'piste' in e && e.piste === null)).toBe(true);
  });

  test('autosave survives reload and the resume banner restores the match', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);
    await recordHit(page);
    await page.waitForTimeout(400);

    await page.reload();
    await expect(page.locator('#s-setup.active')).toBeVisible();
    await expect(page.locator('#resume-banner')).toHaveClass(/on/);
    await expect(page.locator('#resume-text')).toContainText('Resume LEFT vs RIGHT');

    await page.locator('#resume-yes').tap();
    await expect(page.locator('#s-result.active')).toBeVisible();
    await expect(page.locator('#res-score-l')).toHaveText('1');
  });

  test('dismissing the resume banner hides it without deleting the match', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);
    await recordHit(page);
    await page.waitForTimeout(400);

    await page.reload();
    await expect(page.locator('#resume-banner')).toHaveClass(/on/);
    await page.locator('#resume-no').tap();
    await expect(page.locator('#resume-banner')).not.toHaveClass(/on/);

    // still in the library
    await page.locator('#btn-library').tap();
    await expect(page.locator('.lib-row')).toHaveCount(1);
  });

  test('finished match does not trigger the resume banner', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);
    await page.locator('#pause-btn').tap();
    await page.locator('#res-end').tap();
    await page.waitForTimeout(300);

    await page.reload();
    await expect(page.locator('#s-setup.active')).toBeVisible();
    await expect(page.locator('#resume-banner')).not.toHaveClass(/on/);
  });

  test('library lists a saved match and loads it', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);
    await recordHit(page);
    await page.waitForTimeout(400);

    await page.reload();
    await page.locator('#btn-library').tap();
    await expect(page.locator('#s-library.active')).toBeVisible();
    await expect(page.locator('.lib-row')).toHaveCount(1);
    await expect(page.locator('.lib-row-names').first()).toContainText('LEFT');
    await expect(page.locator('.lib-status.live').first()).toBeVisible();

    await page.locator('.lib-load').first().tap();
    await expect(page.locator('#s-result.active')).toBeVisible();
    await expect(page.locator('#res-score-l')).toHaveText('1');
  });

  test('delete removes a match from the library', async ({ page }) => {
    page.on('dialog', d => d.accept());

    await page.goto(APP_PATH);
    await startMatch(page);
    await recordHit(page);
    await page.waitForTimeout(400);
    await page.reload();

    await page.locator('#btn-library').tap();
    await expect(page.locator('.lib-row')).toHaveCount(1);

    await page.locator('.lib-del').first().tap();
    await expect(page.locator('.lib-row')).toHaveCount(0);
    await expect(page.locator('.lib-empty')).toBeVisible();
    expect(await page.evaluate(() => JSON.parse(localStorage.getItem('ufl:index') || '[]').length)).toBe(0);
  });

  test('clear all empties the library and storage', async ({ page }) => {
    page.on('dialog', d => d.accept());

    await page.goto(APP_PATH);
    await startMatch(page);
    await recordHit(page);
    await page.waitForTimeout(400);
    await page.reload();

    await page.locator('#btn-library').tap();
    await expect(page.locator('.lib-row')).toHaveCount(1);

    await page.locator('#lib-clear').tap();
    await expect(page.locator('.lib-row')).toHaveCount(0);
    await expect(page.locator('.lib-empty')).toBeVisible();

    const leftover = await page.evaluate(() =>
      Object.keys(localStorage).filter(k => k.startsWith('ufl:match:')).length);
    expect(leftover).toBe(0);
  });
});

test.describe('overlay video export', () => {
  test('export drawer exposes imported-video controls', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);
    await page.evaluate(() => (window as any).openDrawer());

    await expect(page.locator('#imp-video')).toBeVisible();
    await expect(page.locator('#exp-overlay')).toBeHidden();
    await expect(page.locator('#overlay-status')).toBeVisible();
  });

  test('imported-video setup does not auto-start camera recording', async ({ page }) => {
    await page.goto(APP_PATH);
    await page.evaluate(() => {
      _importVideoFile = new File(['video'], 'bout.webm', { type: 'video/webm' });
      (window as any).__prepareRecordingCalls = 0;
      prepareRecordingBeforeStart = async () => { (window as any).__prepareRecordingCalls++; };
    });

    await startMatch(page);

    const calls = await page.evaluate(() => (window as any).__prepareRecordingCalls);
    expect(calls).toBe(0);
  });

  test('imported-video annotation can continue past clock and score limits', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);

    const state = await page.evaluate(async () => {
      _importVideoFile = new File(['video'], 'bout.webm', { type: 'video/webm' });
      M.timerSec = 0;
      M.scoreL = 14;
      refreshHUD();
      doConfirm('L', 100, true);
      await new Promise(resolve => setTimeout(resolve, 1100));
      stopTimer();
      return {
        finished: M.finished,
        scoreL: M.scoreL,
        timerSec: M.timerSec,
        timerText: document.getElementById('timer-disp')?.textContent,
      };
    });

    expect(state.finished).toBe(false);
    expect(state.scoreL).toBe(15);
    expect(state.timerSec).toBeLessThan(0);
    expect(state.timerText).toBe('0:00');
  });

  test('maps event timestamps across periods for imported overlay replay', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);

    const mapped = await page.evaluate(() => (window as any).overlayEventTime({ ts: 12, period: 2 }));
    expect(mapped).toBe(192);
  });

  test('records video start offset in the match JSON snapshot', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);

    const start = await page.evaluate(() => (window as any).markRecordingStart());
    await page.waitForTimeout(400);
    const snap = await page.evaluate(() => {
      const id = JSON.parse(localStorage.getItem('ufl:index') || '[]')[0].id;
      return JSON.parse(localStorage.getItem('ufl:match:' + id) || 'null');
    });
    expect(start).toBe(0);
    expect(snap.recordingStartBoutTs).toBe(0);
    expect(snap.recordingStartPeriod).toBe(1);
    expect(snap.recordingStartPeriodTs).toBe(0);
  });

  test('can store a pre-clock recording offset for countdown capture', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);

    const start = await page.evaluate(() => (window as any).markRecordingStart(-2.7));
    await page.waitForTimeout(400);
    const snap = await page.evaluate(() => {
      const id = JSON.parse(localStorage.getItem('ufl:index') || '[]')[0].id;
      return JSON.parse(localStorage.getItem('ufl:match:' + id) || 'null');
    });
    expect(start).toBeCloseTo(-2.7);
    expect(snap.recordingStartBoutTs).toBeCloseTo(-2.7);
    expect(snap.recordingStartPeriod).toBe(1);
    expect(snap.recordingStartPeriodTs).toBeCloseTo(-2.7);
  });

  test('ending a match stops active recording immediately', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);

    const stopped = await page.evaluate(() => {
      (window as any).__recorderStopped = false;
      (window as any).__trackStopped = false;
      _camState = 'recording';
      _mediaRec = {
        state: 'recording',
        requestData() {},
        stop() {
          this.state = 'inactive';
          (window as any).__recorderStopped = true;
        },
      } as any;
      _camStream = {
        getTracks() {
          return [{ stop() { (window as any).__trackStopped = true; } }];
        },
      } as any;

      finishMatch();
      return {
        recorder: (window as any).__recorderStopped,
        track: (window as any).__trackStopped,
        camState: _camState,
      };
    });

    expect(stopped).toEqual({ recorder: true, track: true, camState: 'stopping' });
  });

  test('maps recorded video time through clock pauses for overlay export', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);

    const mapped = await page.evaluate(() => {
      M.recordingPauses = [
        { startVideo: 5, endVideo: 10, boutTime: 5 },
      ];
      return {
        beforePause: (window as any).videoTimeToBoutTime(4, 0, true),
        duringPause: (window as any).videoTimeToBoutTime(7, 0, true),
        afterPause: (window as any).videoTimeToBoutTime(12, 0, true),
        importedPath: (window as any).videoTimeToBoutTime(12, 0, false),
      };
    });

    expect(mapped).toEqual({
      beforePause: 4,
      duringPause: 5,
      afterPause: 7,
      importedPath: 12,
    });
  });

  test('recorded pause mapping never rewinds overlay clock', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);

    const mapped = await page.evaluate(() => {
      M.recordingPauses = [
        { startVideo: 10, endVideo: 14, boutTime: 6 },
      ];
      return {
        beforePause: (window as any).videoTimeToBoutTime(12, -2.7, false),
        duringPause: (window as any).videoTimeToBoutTime(12, -2.7, true),
        afterPause: (window as any).videoTimeToBoutTime(16, -2.7, true),
      };
    });

    expect(mapped.beforePause).toBeCloseTo(9.3);
    expect(mapped.duringPause).toBeCloseTo(10 - 2.7);
    expect(mapped.duringPause).toBeGreaterThanOrEqual(10 - 2.7);
    expect(mapped.afterPause).toBeCloseTo(16 - 2.7 - 4);
  });

  test('pause screen contributes to recorded overlay clock pauses', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);

    const pause = await page.evaluate(() => {
      _camState = 'recording';
      _recPerfStart = performance.now() - 10700;
      M.running = true;
      M.timerSec = M.periodDuration - 8;
      stopTimer();

      const openPause = _recPauseOpen
        ? { startVideo: _recPauseOpen.startVideo, boutTime: _recPauseOpen.boutTime }
        : null;

      _recPerfStart = performance.now() - 14700;
      startTimer();

      return {
        openPause,
        savedPause: M.recordingPauses[0],
        mappedDuringPause: (window as any).videoTimeToBoutTime(12, -2.7, true),
        mappedAfterPause: (window as any).videoTimeToBoutTime(16, -2.7, true),
      };
    });

    expect(pause.openPause?.boutTime).toBe(8);
    expect(pause.openPause?.startVideo).toBeGreaterThanOrEqual(10);
    expect(pause.savedPause.boutTime).toBe(8);
    expect(pause.savedPause.endVideo).toBeGreaterThan(pause.savedPause.startVideo);
    expect(pause.mappedDuringPause).toBeCloseTo(8, 2);
    expect(pause.mappedAfterPause).toBeCloseTo(16 - 2.7 - (pause.savedPause.endVideo - pause.savedPause.startVideo), 1);
  });

  test('replays scoreboard state from event timestamps for video export', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);

    const states = await page.evaluate(() => {
      const events = [
        { ts: 7, period: 1, side: 'R', label: 'Simple Attack', isHit: true, actionId: 200 },
        { ts: 13, period: 1, side: 'R', label: 'Counterattack', isHit: true, actionId: 220 },
      ];
      return [
        (window as any).replayStateAt(6, events),
        (window as any).replayStateAt(7, events),
        (window as any).replayStateAt(13, events),
      ].map((s: any) => ({ scoreL: s.scoreL, scoreR: s.scoreR, timerSec: s.timerSec }));
    });
    expect(states).toEqual([
      { scoreL: 0, scoreR: 0, timerSec: 174 },
      { scoreL: 0, scoreR: 1, timerSec: 173 },
      { scoreL: 0, scoreR: 2, timerSec: 167 },
    ]);
  });

  test('freezes match events for overlay rendering even if the live match changes', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);

    const scoreFromSnapshot = await page.evaluate(() => {
      const liveMatch = M;
      liveMatch.events = [
        { ts: 7, period: 1, side: 'R', label: 'Simple Attack', isHit: true, actionId: 200 },
      ];
      const snap = (window as any).snapshotMatchForOverlay(liveMatch);
      liveMatch.events = [];

      const saved = M;
      try {
        M = snap;
        return (window as any).replayStateAt(10).scoreR;
      } finally {
        M = saved;
      }
    });

    expect(scoreFromSnapshot).toBe(1);
  });

  test('unsupported overlay rendering shows an error without breaking exports', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);
    await page.evaluate(() => (window as any).openDrawer());

    await page.locator('#video-input').setInputFiles({
      name: 'clip.mp4',
      mimeType: 'video/mp4',
      buffer: Buffer.from('not a real video'),
    });
    await expect(page.locator('#exp-overlay')).toBeVisible();
    await expect(page.locator('#overlay-status')).toContainText('clip.mp4 ready');

    await page.evaluate(() => { (window as any).MediaRecorder = undefined; });
    await page.locator('#exp-overlay').tap();
    await expect(page.locator('#overlay-status')).toContainText('Overlay export is not supported');
    await expect(page.locator('#exp-json')).toBeEnabled();
  });
});

test.describe('native video review', () => {
  test('opens latest pending action with a native review payload around the event timestamp', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const payload = await page.evaluate(() => {
      (window as any).__reviewPayload = null;
      (window as any).AndroidVideo = {
        exportOverlay() {},
        startVideoReview(json: string) { (window as any).__reviewPayload = JSON.parse(json); },
      };
      _nativeImportVideo = { uri: 'content://review/source.mp4', durationMs: 30000 };
      M.events = [
        { ts: 8, period: 1, side: 'L', actionId: 100, label: 'Simple Attack', emoji: 'A', isHit: true, reviewed: true },
        { ts: 12, period: 1, side: 'R', actionId: 212, label: 'Point in Line', emoji: 'P', isHit: false },
      ];
      (window as any).startVideoReviewForIndex();
      return (window as any).__reviewPayload;
    });

    expect(payload.eventIndex).toBe(1);
    expect(payload.sourceUri).toBe('content://review/source.mp4');
    expect(payload.eventVideoTime).toBe(12);
    expect(payload.clipStart).toBe(7);
    expect(payload.clipEnd).toBe(14);
    expect(payload.playbackRate).toBe(0.5);
    expect(payload.title).toContain('POINT IN LINE');
    expect(payload.actionSideLabel).toBe('RIGHT ACTION');
    expect(payload.actionLabel).toBe('POINT IN LINE');
    expect(payload.resultLabel).toBe('OFF TARGET');
    expect(payload.leftSummary).toBe('LEFT 1');
    expect(payload.rightSummary).toBe('0 RIGHT');
    expect(payload.timerText).toBe('2:48  P1');
  });

  test('review keep and correction preserve audit fields and recompute score', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      (window as any).AndroidVideo = {
        closeVideoReview() {},
        startVideoReview() {},
      };
      M.events = [
        { ts: 8, period: 1, side: 'L', actionId: 100, label: 'Simple Attack', emoji: 'A', isHit: true },
        { ts: 10, period: 1, side: 'R', actionId: 200, label: 'Simple Attack', emoji: 'A', isHit: true },
      ];
      (window as any).recomputeMatchScoreFromEvents();
      (window as any).onAndroidVideoReviewKeep(JSON.stringify({
        eventIndex: 1,
        chosenTime: 10.2,
        clipStart: 5,
        clipEnd: 12,
        playbackRate: 0.25,
      }));
      (window as any).onAndroidVideoReviewEdit(JSON.stringify({
        eventIndex: 0,
        chosenTime: 8.1,
        clipStart: 3,
        clipEnd: 10,
        playbackRate: 0.5,
      }));
      (window as any).doConfirm('L', 100, false);
      return {
        scoreL: M.scoreL,
        scoreR: M.scoreR,
        kept: M.events[1],
        corrected: M.events[0],
      };
    });

    expect(result.scoreL).toBe(0);
    expect(result.scoreR).toBe(1);
    expect(result.kept.reviewStatus).toBe('confirmed');
    expect(result.kept.videoReview.playbackRate).toBe(0.25);
    expect(result.corrected.reviewStatus).toBe('corrected');
    expect(result.corrected.originalActionId).toBe(100);
    expect(result.corrected.originalIsHit).toBe(true);
    expect(result.corrected.isHit).toBe(false);
    expect(result.corrected.videoReview.chosenTime).toBe(8.1);
  });
});
