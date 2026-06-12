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

  test('recorded native export sends all recording segments for stitching', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const payload = await page.evaluate(() => {
      (window as any).__exportPayload = null;
      (window as any).AndroidVideo = {
        exportOverlay(json: string) { (window as any).__exportPayload = JSON.parse(json); },
      };
      M.recordingSegments = [
        { uri: 'content://recorded/first.mp4', savedUri: 'content://recorded/first.mp4', durationMs: 10000, startBoutTs: 0, sourceType: 'recorded' },
        { uri: 'content://recorded/second.mp4', savedUri: 'content://recorded/second.mp4', durationMs: 12000, startBoutTs: 12, sourceType: 'recorded' },
      ];
      _nativeRecordedPayload = M.recordingSegments[1];
      _nativeRecordedVideo = 'content://recorded/second.mp4';
      (window as any).exportVideo();
      return (window as any).__exportPayload;
    });

    expect(payload.sourceType).toBe('recorded');
    expect(payload.sourceUri).toBe('content://recorded/first.mp4');
    expect(payload.durationMs).toBe(22000);
    expect(payload.segments).toHaveLength(2);
    expect(payload.segments[0].startBoutTs).toBe(0);
    expect(payload.segments[1].startBoutTs).toBe(12);
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
    expect(payload.showReviewOverlay).toBe(false);
    expect(payload.title).toContain('POINT IN LINE');
    expect(payload.match.nameL).toBe('LEFT');
    expect(payload.match.nameR).toBe('RIGHT');
    expect(payload.match.events).toHaveLength(2);
    expect(payload.match.events[1].label).toBe('Point in Line');
  });

  test('review done and correction preserve audit fields and recompute score', async ({ page }) => {
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

  test('video review action launches review instead of recording an event', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      (window as any).__reviewPayload = null;
      (window as any).AndroidVideo = {
        startVideoReview(json: string) { (window as any).__reviewPayload = JSON.parse(json); },
      };
      _nativeImportVideo = { uri: 'content://review/source.mp4', durationMs: 30000 };
      M.events = [
        { ts: 9, period: 1, side: 'R', actionId: 200, label: 'Simple Attack', emoji: 'A', isHit: true },
      ];
      const before = M.events.length;
      (window as any).showConfirm('L', 3);
      return {
        before,
        after: M.events.length,
        payload: (window as any).__reviewPayload,
        confirmVisible: document.getElementById('conf-l')?.classList.contains('on'),
      };
    });

    expect(result.after).toBe(result.before);
    expect(result.confirmVisible).toBe(false);
    expect(result.payload.eventIndex).toBe(0);
    expect(result.payload.sourceUri).toBe('content://review/source.mp4');
    expect(result.payload.showReviewOverlay).toBe(false);
  });

  test('done confirms the current review and exits instead of auto-opening another action', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      const payloads: any[] = [];
      let closeCalls = 0;
      (window as any).AndroidVideo = {
        closeVideoReview() { closeCalls++; },
        startVideoReview(json: string) { payloads.push(JSON.parse(json)); },
      };
      M.events = [
        { ts: 8, period: 1, side: 'L', actionId: 100, label: 'Simple Attack', emoji: 'A', isHit: true },
        { ts: 10, period: 1, side: 'R', actionId: 200, label: 'Simple Attack', emoji: 'A', isHit: true },
      ];
      (window as any).onAndroidVideoReviewKeep(JSON.stringify({
        eventIndex: 1,
        chosenTime: 10,
        clipStart: 5,
        clipEnd: 12,
        playbackRate: 0.5,
      }));
      return {
        closeCalls,
        payloads,
        reviewed: M.events[1].reviewed,
        reviewStatus: M.events[1].reviewStatus,
      };
    });

    expect(result.closeCalls).toBe(1);
    expect(result.payloads).toHaveLength(0);
    expect(result.reviewed).toBe(true);
    expect(result.reviewStatus).toBe('confirmed');
  });

  test('imported video review resumes annotation after done', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      (window as any).__resumeCalls = 0;
      (window as any).AndroidVideo = {
        exportOverlay() {},
        closeVideoReview() {},
        startVideoReview() {},
        playImportedPreview() { (window as any).__resumeCalls++; },
        pauseImportedPreview() {},
      };
      _nativeImportVideo = { uri: 'content://review/source.mp4', durationMs: 30000 };
      _importVideoFile = { name: 'source.mp4', nativeUri: 'content://review/source.mp4' } as any;
      M.events = [
        { ts: 9, period: 1, side: 'R', actionId: 200, label: 'Simple Attack', emoji: 'A', isHit: true },
      ];
      M.running = true;
      (window as any).startVideoReviewForIndex(0);
      const stoppedForReview = M.running === false;
      (window as any).onAndroidVideoReviewKeep(JSON.stringify({
        eventIndex: 0,
        chosenTime: 9,
        clipStart: 4,
        clipEnd: 11,
        playbackRate: 0.5,
      }));
      const runningAfterDone = M.running;
      if (_timerInterval) {
        clearInterval(_timerInterval);
        _timerInterval = null;
      }
      return {
        stoppedForReview,
        runningAfterDone,
        resumeCalls: (window as any).__resumeCalls,
        reviewStatus: M.events[0].reviewStatus,
      };
    });

    expect(result.stoppedForReview).toBe(true);
    expect(result.runningAfterDone).toBe(true);
    expect(result.resumeCalls).toBeGreaterThan(0);
    expect(result.reviewStatus).toBe('confirmed');
  });

  test('canceling a review correction returns to the same native review', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(async () => {
      const payloads: any[] = [];
      (window as any).AndroidVideo = {
        closeVideoReview() {},
        startVideoReview(json: string) { payloads.push(JSON.parse(json)); },
      };
      _nativeImportVideo = { uri: 'content://review/source.mp4', durationMs: 30000 };
      M.events = [
        { ts: 8, period: 1, side: 'R', actionId: 200, label: 'Simple Attack', emoji: 'A', isHit: true },
      ];
      (window as any).onAndroidVideoReviewEdit(JSON.stringify({
        eventIndex: 0,
        chosenTime: 8.1,
        clipStart: 3,
        clipEnd: 10,
        playbackRate: 0.5,
      }));
      (window as any).showConfirm('R', 200);
      document.getElementById('conf-r-no')?.dispatchEvent(new Event('touchend', { bubbles: true, cancelable: true }));
      await new Promise(resolve => setTimeout(resolve, 200));
      return {
        events: M.events,
        payloads,
        editIndex: _reviewEditId,
        running: M.running,
      };
    });

    expect(result.events).toHaveLength(1);
    expect(result.events[0].reviewStatus).toBeUndefined();
    expect(result.events[0].actionId).toBe(200);
    expect(result.events[0].isHit).toBe(true);
    expect(result.payloads).toHaveLength(1);
    expect(result.payloads[0].eventIndex).toBe(0);
    expect(result.payloads[0].sourceUri).toBe('content://review/source.mp4');
    expect(result.editIndex).toBeNull();
    expect(result.running).toBe(false);
  });

  test('review correction can change the fencer side', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      (window as any).AndroidVideo = {
        closeVideoReview() {},
        startVideoReview() {},
      };
      M.events = [
        { ts: 8, period: 1, side: 'R', actionId: 210, label: 'Parry-Riposte', emoji: 'P', isHit: true },
      ];
      (window as any).recomputeMatchScoreFromEvents();
      (window as any).onAndroidVideoReviewEdit(JSON.stringify({
        eventIndex: 0,
        chosenTime: 8.2,
        clipStart: 3,
        clipEnd: 10,
        playbackRate: 0.5,
      }));
      (window as any).doConfirm('L', 103, true);
      return {
        scoreL: M.scoreL,
        scoreR: M.scoreR,
        corrected: M.events[0],
      };
    });

    expect(result.scoreL).toBe(1);
    expect(result.scoreR).toBe(0);
    expect(result.corrected.side).toBe('L');
    expect(result.corrected.actionId).toBe(103);
    expect(result.corrected.label).toBe('Beat Attack');
    expect(result.corrected.originalSide).toBe('R');
    expect(result.corrected.originalActionId).toBe(210);
    expect(result.corrected.reviewStatus).toBe('corrected');
  });

  test('video review action segments active native recording and edit resumes recording', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    // clickStart auto-starts the browser camera; deny it deterministically and
    // let the chain settle, or its late rejection resets the faked _camState
    // mid-test via resetCamUI().
    await page.evaluate(() => {
      navigator.mediaDevices.getUserMedia = () =>
        Promise.reject(Object.assign(new Error('denied'), { name: 'NotAllowedError' }));
    });
    await startMatch(page);
    await page.waitForFunction(() => _camState === 'idle');

    const result = await page.evaluate(async () => {
      const reviewPayloads: any[] = [];
      let stopped = false;
      let prepared = false;
      let restarted = false;
      (window as any).AndroidVideo = {
        prepareNativeRecording() {
          prepared = true;
          setTimeout(() => (window as any).onAndroidRecordingReady(), 0);
        },
        startNativeRecording() {
          restarted = true;
          setTimeout(() => (window as any).onAndroidRecordingStarted(), 0);
        },
        stopNativeRecording() {
          stopped = true;
          setTimeout(() => {
            (window as any).onAndroidRecordingStopped(JSON.stringify({
              sourceType: 'recorded',
              uri: 'content://recorded/review.mp4',
              savedUri: 'content://recorded/review.mp4',
              displayName: 'review.mp4',
              durationMs: 30000,
              overlaid: true,
            }));
          }, 0);
        },
        closeVideoReview() {},
        clearNativeRecordingPreview() {},
        startVideoReview(json: string) { reviewPayloads.push(JSON.parse(json)); },
      };
      M.events = [
        { ts: 8, period: 1, side: 'R', actionId: 200, label: 'Simple Attack', emoji: 'A', isHit: true },
      ];
      M.recordingStartBoutTs = 0;
      _camState = 'recording';
      _nativeRecordingActive = true;
      const launched = (window as any).startVideoReviewForIndex(0);
      await new Promise(resolve => setTimeout(resolve, 50));
      (window as any).onAndroidVideoReviewEdit(JSON.stringify({
        eventIndex: 0,
        chosenTime: 8.1,
        clipStart: 3,
        clipEnd: 10,
        playbackRate: 0.5,
      }));
      await new Promise(resolve => {
        const startedAt = Date.now();
        const tick = () => {
          if (_camState === 'recording' || Date.now() - startedAt > 1000) resolve(undefined);
          else setTimeout(tick, 20);
        };
        tick();
      });
      (window as any).doConfirm('L', 103, true);
      await new Promise(resolve => setTimeout(resolve, 320));
      const running = M.running;
      if (_timerInterval) {
        clearInterval(_timerInterval);
        _timerInterval = null;
      }
      return {
        launched,
        stopped,
        prepared,
        restarted,
        reviewPayloads,
        camState: _camState,
        recordingActive: _nativeRecordingActive,
        running,
        corrected: M.events[0],
        restartPauses: M.recordingPauses,
      };
    });

    expect(result.launched).toBe(true);
    expect(result.stopped).toBe(true);
    expect(result.prepared).toBe(true);
    expect(result.restarted).toBe(true);
    expect(result.camState).toBe('recording');
    expect(result.recordingActive).toBe(true);
    // Only the initial review payload: correcting the last pending action no
    // longer re-opens the review (it would loop with nothing left to review).
    expect(result.reviewPayloads).toHaveLength(1);
    expect(result.reviewPayloads[0].sourceType).toBe('recorded');
    expect(result.reviewPayloads[0].sourceUri).toBe('content://recorded/review.mp4');
    expect(result.reviewPayloads[0].eventIndex).toBe(0);
    // Correcting the last pending action resumes the bout clock.
    expect(result.running).toBe(true);
    // The restarted recording ran under a parked clock until the correction
    // was saved: that dead time must be recorded as a pause span from video 0,
    // or later events map too early into the footage.
    expect(result.restartPauses).toHaveLength(1);
    expect(result.restartPauses[0].startVideo).toBe(0);
    expect(result.restartPauses[0].endVideo).toBeGreaterThan(0);
    expect(result.corrected.side).toBe('L');
    expect(result.corrected.actionId).toBe(103);
  });

  test('recorded video review selects the segment containing the action timestamp', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const payload = await page.evaluate(() => {
      (window as any).__reviewPayload = null;
      (window as any).AndroidVideo = {
        startVideoReview(json: string) { (window as any).__reviewPayload = JSON.parse(json); },
      };
      M.events = [
        { ts: 4, period: 1, side: 'L', actionId: 100, label: 'Simple Attack', emoji: 'A', isHit: true },
        { ts: 18, period: 1, side: 'R', actionId: 200, label: 'Simple Attack', emoji: 'A', isHit: true },
      ];
      M.recordingSegments = [
        { uri: 'content://recorded/first.mp4', savedUri: 'content://recorded/first.mp4', durationMs: 10000, startBoutTs: 0, sourceType: 'recorded' },
        { uri: 'content://recorded/second.mp4', savedUri: 'content://recorded/second.mp4', durationMs: 10000, startBoutTs: 12, sourceType: 'recorded' },
      ];
      (window as any).startVideoReviewForIndex(1);
      return (window as any).__reviewPayload;
    });

    expect(payload.sourceType).toBe('recorded');
    expect(payload.sourceUri).toBe('content://recorded/second.mp4');
    expect(payload.eventVideoTime).toBe(6);
    expect(payload.clipStart).toBe(1);
  });

  test('review decisions resolve events by stable id even if the list shifts', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      (window as any).__reviewPayload = null;
      (window as any).AndroidVideo = {
        closeVideoReview() {},
        startVideoReview(json: string) { (window as any).__reviewPayload = JSON.parse(json); },
      };
      _nativeImportVideo = { uri: 'content://review/source.mp4', durationMs: 30000 };
      M.events = [
        { ts: 8, period: 1, side: 'L', actionId: 100, label: 'Simple Attack', emoji: 'A', isHit: true },
        { ts: 12, period: 1, side: 'R', actionId: 200, label: 'Simple Attack', emoji: 'A', isHit: true },
      ];
      (window as any).startVideoReviewForIndex(1);
      const payload = (window as any).__reviewPayload;
      // A new event lands at the front while the review is open, shifting indices.
      M.events.unshift({ id: 'ev-injected', ts: 2, period: 1, side: 'L', actionId: 101, label: 'Compound', emoji: 'C', isHit: false, reviewed: true });
      (window as any).onAndroidVideoReviewKeep(JSON.stringify({
        eventId: payload.eventId,
        eventIndex: payload.eventIndex, // stale: now points at a different event
        chosenTime: 12,
        clipStart: 7,
        clipEnd: 14,
        playbackRate: 0.5,
      }));
      return {
        payloadEventId: payload.eventId,
        confirmed: M.events.filter((e: any) => e.reviewStatus === 'confirmed').map((e: any) => `${e.label}:${e.side}`),
      };
    });

    expect(result.payloadEventId).toBeTruthy();
    expect(result.confirmed).toEqual(['Simple Attack:R']);
  });

  test('navigating past the last action keeps the current review open', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      const payloads: any[] = [];
      (window as any).AndroidVideo = {
        closeVideoReview() {},
        startVideoReview(json: string) { payloads.push(JSON.parse(json)); },
      };
      _nativeImportVideo = { uri: 'content://review/source.mp4', durationMs: 30000 };
      M.events = [
        { ts: 8, period: 1, side: 'L', actionId: 100, label: 'Simple Attack', emoji: 'A', isHit: true },
      ];
      (window as any).onAndroidVideoReviewNavigate(JSON.stringify({ eventIndex: 0, direction: 1 }));
      (window as any).onAndroidVideoReviewNavigate(JSON.stringify({ eventIndex: 0, direction: -1 }));
      return { payloads };
    });

    expect(result.payloads).toHaveLength(0);
  });

  test('importing while recording asks for confirmation first', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    // Same camera denial as the segmenting test above: a late getUserMedia
    // rejection would reset the faked _camState before the guard is exercised.
    await page.evaluate(() => {
      navigator.mediaDevices.getUserMedia = () =>
        Promise.reject(Object.assign(new Error('denied'), { name: 'NotAllowedError' }));
    });
    await startMatch(page);
    await page.waitForFunction(() => _camState === 'idle');

    const result = await page.evaluate(() => {
      let selectCalls = 0;
      (window as any).AndroidVideo = {
        exportOverlay() {},
        selectVideo() { selectCalls++; },
      };
      _camState = 'recording';
      const origConfirm = window.confirm;
      window.confirm = () => false;
      (window as any).openVideoPicker();
      const afterDismiss = selectCalls;
      window.confirm = () => true;
      (window as any).openVideoPicker();
      window.confirm = origConfirm;
      _camState = 'idle';
      return { afterDismiss, afterAccept: selectCalls };
    });

    expect(result.afterDismiss).toBe(0);
    expect(result.afterAccept).toBe(1);
  });

  test('call stands records a review event in the timeline', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      (window as any).AndroidVideo = {
        closeVideoReview() {},
        startVideoReview() {},
      };
      _nativeImportVideo = { uri: 'content://review/source.mp4', durationMs: 30000 };
      M.events = [
        { ts: 8, period: 1, side: 'R', actionId: 200, label: 'Simple Attack', emoji: 'A', isHit: true },
      ];
      (window as any).startVideoReviewForIndex(0);
      (window as any).onAndroidVideoReviewKeep(JSON.stringify({
        eventId: M.events[0].id,
        eventIndex: 0,
        chosenTime: 8,
        clipStart: 3,
        clipEnd: 10,
        playbackRate: 0.5,
      }));
      return {
        target: M.events[0],
        review: M.events[1],
      };
    });

    expect(result.target.reviewStatus).toBe('confirmed');
    expect(result.review.actionId).toBe(3);
    expect(result.review.side).toBe('C');
    expect(result.review.reviewOutcome).toBe('call-stands');
    expect(result.review.targetEventId).toBe(result.target.id);
  });

  test('overturn with annul zeroes the touch and records the outcome', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      (window as any).AndroidVideo = {
        closeVideoReview() {},
        startVideoReview() {},
      };
      _nativeImportVideo = { uri: 'content://review/source.mp4', durationMs: 30000 };
      M.events = [
        { ts: 8, period: 1, side: 'R', actionId: 200, label: 'Simple Attack', emoji: 'A', isHit: true },
      ];
      (window as any).recomputeMatchScoreFromEvents();
      const scoreBefore = { l: M.scoreL, r: M.scoreR };
      (window as any).onAndroidVideoReviewEdit(JSON.stringify({
        eventIndex: 0,
        chosenTime: 8.1,
        clipStart: 3,
        clipEnd: 10,
        playbackRate: 0.5,
      }));
      const chooserVisible = document.getElementById('overturn-chooser')?.classList.contains('on');
      document.getElementById('ovr-annul')?.dispatchEvent(new Event('touchend', { bubbles: true, cancelable: true }));
      const runningAfterAnnul = M.running;
      if (_timerInterval) {
        clearInterval(_timerInterval);
        _timerInterval = null;
      }
      return {
        scoreBefore,
        scoreL: M.scoreL,
        scoreR: M.scoreR,
        chooserVisible,
        runningAfterAnnul,
        annulled: M.events[0],
        review: M.events[1],
      };
    });

    expect(result.chooserVisible).toBe(true);
    expect(result.runningAfterAnnul).toBe(true);
    expect(result.scoreBefore).toEqual({ l: 0, r: 1 });
    expect(result.scoreL).toBe(0);
    expect(result.scoreR).toBe(0);
    expect(result.annulled.isHit).toBe(false);
    expect(result.annulled.reviewStatus).toBe('overturned');
    expect(result.annulled.overturnOutcome).toBe('annulled');
    expect(result.annulled.originalIsHit).toBe(true);
    expect(result.annulled.actionId).toBe(200);
    expect(result.review.reviewOutcome).toBe('overturned:annulled');
    expect(result.review.targetEventId).toBe(result.annulled.id);
  });

  test('overturn awarding the touch opens the radial for the other fencer', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      (window as any).AndroidVideo = {
        closeVideoReview() {},
        startVideoReview() {},
      };
      _nativeImportVideo = { uri: 'content://review/source.mp4', durationMs: 30000 };
      M.events = [
        { ts: 8, period: 1, side: 'R', actionId: 210, label: 'Parry-Riposte', emoji: 'P', isHit: true },
      ];
      (window as any).recomputeMatchScoreFromEvents();
      (window as any).onAndroidVideoReviewEdit(JSON.stringify({
        eventIndex: 0,
        chosenTime: 8.2,
        clipStart: 3,
        clipEnd: 10,
        playbackRate: 0.5,
      }));
      document.getElementById('ovr-other')?.dispatchEvent(new Event('touchend', { bubbles: true, cancelable: true }));
      const radSide = _radSide;
      (window as any).doConfirm('L', 103, true);
      return {
        radSide,
        scoreL: M.scoreL,
        scoreR: M.scoreR,
        overturned: M.events[0],
        review: M.events[1],
      };
    });

    expect(result.radSide).toBe('L');
    expect(result.scoreL).toBe(1);
    expect(result.scoreR).toBe(0);
    expect(result.overturned.side).toBe('L');
    expect(result.overturned.reviewStatus).toBe('overturned');
    expect(result.overturned.overturnOutcome).toBe('awarded-other');
    expect(result.overturned.originalSide).toBe('R');
    expect(result.review.reviewOutcome).toBe('overturned:awarded-other');
  });

  test('canceling the overturn chooser returns to the same review', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(async () => {
      const payloads: any[] = [];
      (window as any).AndroidVideo = {
        closeVideoReview() {},
        startVideoReview(json: string) { payloads.push(JSON.parse(json)); },
      };
      _nativeImportVideo = { uri: 'content://review/source.mp4', durationMs: 30000 };
      M.events = [
        { ts: 8, period: 1, side: 'R', actionId: 200, label: 'Simple Attack', emoji: 'A', isHit: true },
      ];
      (window as any).onAndroidVideoReviewEdit(JSON.stringify({
        eventIndex: 0,
        chosenTime: 8.1,
        clipStart: 3,
        clipEnd: 10,
        playbackRate: 0.5,
      }));
      document.getElementById('ovr-cancel')?.dispatchEvent(new Event('touchend', { bubbles: true, cancelable: true }));
      await new Promise(resolve => setTimeout(resolve, 200));
      return {
        chooserVisible: document.getElementById('overturn-chooser')?.classList.contains('on'),
        events: M.events.length,
        eventUntouched: !M.events[0].reviewed,
        payloads: payloads.length,
        editId: _reviewEditId,
      };
    });

    expect(result.chooserVisible).toBe(false);
    expect(result.events).toBe(1);
    expect(result.eventUntouched).toBe(true);
    expect(result.payloads).toBe(1);
    expect(result.editId).toBeNull();
  });

  test('correcting the last pending action resumes the match clock', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      (window as any).AndroidVideo = {
        closeVideoReview() {},
        startVideoReview() {},
      };
      M.events = [
        { ts: 8, period: 1, side: 'R', actionId: 210, label: 'Parry-Riposte', emoji: 'P', isHit: true },
      ];
      (window as any).onAndroidVideoReviewEdit(JSON.stringify({
        eventIndex: 0,
        chosenTime: 8.2,
        clipStart: 3,
        clipEnd: 10,
        playbackRate: 0.5,
      }));
      const runningDuringCorrection = M.running;
      (window as any).doConfirm('L', 103, true);
      const runningAfterCorrection = M.running;
      if (_timerInterval) {
        clearInterval(_timerInterval);
        _timerInterval = null;
      }
      return { runningDuringCorrection, runningAfterCorrection };
    });

    expect(result.runningDuringCorrection).toBe(false);
    expect(result.runningAfterCorrection).toBe(true);
  });

  test('review opens the latest pending action by bout time, not array order', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      (window as any).__reviewPayload = null;
      (window as any).AndroidVideo = {
        startVideoReview(json: string) { (window as any).__reviewPayload = JSON.parse(json); },
      };
      _nativeImportVideo = { uri: 'content://review/source.mp4', durationMs: 120000 };
      // Annotated out of order: the 30s action was recorded after the 50s one.
      M.events = [
        { ts: 50, period: 1, side: 'L', actionId: 101, label: 'Compound', emoji: 'C', isHit: false },
        { ts: 30, period: 1, side: 'R', actionId: 200, label: 'Simple Attack', emoji: 'A', isHit: true },
      ];
      (window as any).startVideoReviewForIndex();
      return (window as any).__reviewPayload;
    });

    expect(result.eventIndex).toBe(0); // ts 50 is the latest by bout time
  });

  test('the review button opens the latest action even when it is already reviewed', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      (window as any).__reviewPayload = null;
      (window as any).AndroidVideo = {
        startVideoReview(json: string) { (window as any).__reviewPayload = JSON.parse(json); },
      };
      _nativeImportVideo = { uri: 'content://review/source.mp4', durationMs: 120000 };
      M.events = [
        { ts: 10, period: 1, side: 'L', actionId: 100, label: 'Simple Attack', emoji: 'A', isHit: true },
        { ts: 30, period: 1, side: 'R', actionId: 200, label: 'Simple Attack', emoji: 'A', isHit: true, reviewed: true, reviewStatus: 'confirmed' },
      ];
      (window as any).startVideoReviewForIndex();
      return (window as any).__reviewPayload;
    });

    // The reviewed 30s action is still the latest; the pending 10s one is
    // reachable via PREV inside the review.
    expect(result.eventIndex).toBe(1);
  });

  test('reviewing an action from the active recording stops it instead of replaying the previous segment', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    // Camera denial, as in the other recording-state tests.
    await page.evaluate(() => {
      navigator.mediaDevices.getUserMedia = () =>
        Promise.reject(Object.assign(new Error('denied'), { name: 'NotAllowedError' }));
    });
    await startMatch(page);
    await page.waitForFunction(() => _camState === 'idle');

    const result = await page.evaluate(async () => {
      const payloads: any[] = [];
      let stopped = false;
      (window as any).AndroidVideo = {
        closeVideoReview() {},
        clearNativeRecordingPreview() {},
        prepareNativeRecording() { setTimeout(() => (window as any).onAndroidRecordingReady(), 0); },
        startNativeRecording() { setTimeout(() => (window as any).onAndroidRecordingStarted(), 0); },
        stopNativeRecording() {
          stopped = true;
          setTimeout(() => {
            (window as any).onAndroidRecordingStopped(JSON.stringify({
              sourceType: 'recorded',
              uri: 'content://recorded/second.mp4',
              savedUri: 'content://recorded/second.mp4',
              displayName: 'second.mp4',
              durationMs: 25000,
            }));
          }, 0);
        },
        startVideoReview(json: string) { payloads.push(JSON.parse(json)); },
      };
      // Segment 1 covers bout 0-15s; the camera is rolling again (segment 2,
      // started at bout 15s, not yet finalized).
      M.recordingSegments = [
        { uri: 'content://recorded/first.mp4', savedUri: 'content://recorded/first.mp4', durationMs: 15000, startBoutTs: 0, pauses: [], sourceType: 'recorded' },
      ];
      M.recordingStartBoutTs = 15;
      _recBoutStart = 15;
      _camState = 'recording';
      _nativeRecordingActive = true;
      M.events = [
        { ts: 30, period: 1, side: 'R', actionId: 200, label: 'Simple Attack', emoji: 'A', isHit: true },
      ];
      (window as any).startVideoReviewForIndex(0);
      await new Promise(resolve => setTimeout(resolve, 100));
      return {
        stopped,
        payloads,
      };
    });

    // The action at bout 30s is NOT in segment 1: the recording must be
    // stopped and the review opened on the fresh segment.
    expect(result.stopped).toBe(true);
    expect(result.payloads).toHaveLength(1);
    expect(result.payloads[0].sourceUri).toBe('content://recorded/second.mp4');
    expect(result.payloads[0].eventVideoTime).toBeCloseTo(15, 1); // bout 30 - segment start 15
  });

  test('exiting a review of stored footage resumes the bout clock', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      (window as any).AndroidVideo = {
        closeVideoReview() {},
        startVideoReview() {},
      };
      // Footage already stored; no recording active, no import - the review
      // opens without setting any resume flag.
      M.recordingSegments = [
        { uri: 'content://recorded/first.mp4', savedUri: 'content://recorded/first.mp4', durationMs: 20000, startBoutTs: 0, pauses: [], sourceType: 'recorded' },
      ];
      M.events = [
        { ts: 8, period: 1, side: 'L', actionId: 100, label: 'Simple Attack', emoji: 'A', isHit: true },
      ];
      (window as any).startVideoReviewForIndex(0);
      const stoppedDuringReview = !M.running;
      (window as any).onAndroidVideoReviewKeep(JSON.stringify({
        eventId: M.events[0].id,
        eventIndex: 0,
        chosenTime: 8,
        clipStart: 3,
        clipEnd: 10,
        playbackRate: 0.5,
      }));
      const runningAfterKeep = M.running;
      if (_timerInterval) {
        clearInterval(_timerInterval);
        _timerInterval = null;
      }
      return { stoppedDuringReview, runningAfterKeep };
    });

    expect(result.stoppedDuringReview).toBe(true); // clock parked while reviewing
    expect(result.runningAfterKeep).toBe(true);    // and resumes on CALL STANDS
  });

  test('status messages surface as a toast when the drawer is closed', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      // No source, no events: tapping the review button reports a status.
      (window as any).startVideoReviewForIndex();
      const toast = {
        text: document.getElementById('hint-pill')?.textContent,
        visible: document.getElementById('hint-pill')?.classList.contains('on'),
      };
      // With the drawer open the message stays in the drawer status line.
      (window as any).openDrawer();
      document.getElementById('hint-pill')?.classList.remove('on');
      (window as any).setOverlayStatus('Drawer-only message.', false);
      const whileDrawerOpen = document.getElementById('hint-pill')?.classList.contains('on');
      (window as any).closeDrawer();
      return { toast, whileDrawerOpen, drawerStatus: document.getElementById('overlay-status')?.textContent };
    });

    expect(result.toast.visible).toBe(true);
    expect(result.toast.text).toContain('No action available');
    expect(result.whileDrawerOpen).toBe(false);
    expect(result.drawerStatus).toBe('Drawer-only message.');
  });

  test('a closing radial overlay cannot swallow taps', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      (window as any).openPie('L');
      const openState = document.getElementById('radial-overlay')?.style.pointerEvents;
      (window as any).closePie('L', false);
      const ov = document.getElementById('radial-overlay');
      return {
        openState,
        closingPointerEvents: ov?.style.pointerEvents,
        stillDisplayed: ov?.style.display, // flex during the 220ms fade
      };
    });

    expect(result.openState).toBe('');
    expect(result.closingPointerEvents).toBe('none');
    expect(result.stillDisplayed).toBe('flex');
  });

  test('the drawer review button shows while the recording is still rolling', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      (window as any).AndroidVideo = { exportOverlay() {}, startVideoReview() {} };
      M.events = [
        { ts: 8, period: 1, side: 'L', actionId: 100, label: 'Simple Attack', emoji: 'A', isHit: true },
      ];
      _camState = 'recording';
      _nativeRecordingActive = true;
      (window as any).openDrawer();
      const display = document.getElementById('video-review')?.style.display;
      (window as any).closeDrawer();
      _camState = 'idle';
      _nativeRecordingActive = false;
      return { display };
    });

    expect(result.display).toBe('inline-block');
  });

  test('a review decision returns to the match even with other pending reviews', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(async () => {
      const payloads: any[] = [];
      (window as any).AndroidVideo = {
        closeVideoReview() {},
        startVideoReview(json: string) { payloads.push(JSON.parse(json)); },
      };
      _nativeImportVideo = { uri: 'content://review/source.mp4', durationMs: 120000 };
      M.events = [
        { ts: 8, period: 1, side: 'R', actionId: 200, label: 'Simple Attack', emoji: 'A', isHit: true },
        { ts: 20, period: 1, side: 'L', actionId: 100, label: 'Simple Attack', emoji: 'A', isHit: true },
      ];
      (window as any).onAndroidVideoReviewEdit(JSON.stringify({
        eventIndex: 0,
        chosenTime: 8.1,
        clipStart: 3,
        clipEnd: 10,
        playbackRate: 0.5,
      }));
      (window as any).doConfirm('L', 103, true);
      await new Promise(resolve => setTimeout(resolve, 350));
      const running = M.running;
      if (_timerInterval) {
        clearInterval(_timerInterval);
        _timerInterval = null;
      }
      return {
        reviewLaunches: payloads.length,
        running,
        stale: { startedFromRecording: _reviewStartedFromRecording, cachedSource: _activeVideoReviewSource },
      };
    });

    expect(result.reviewLaunches).toBe(0); // decided -> back to the match, no auto-reopen
    expect(result.running).toBe(true);
    expect(result.stale.startedFromRecording).toBe(false);
    expect(result.stale.cachedSource).toBeNull();
  });

  test('export overlay shows the fencer action with a review badge, never a referee pill', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      M.events = [
        { ts: 10, period: 1, side: 'R', actionId: 200, label: 'Simple Attack', emoji: 'A', isHit: true, reviewed: true, reviewStatus: 'corrected' },
        { ts: 10, period: 1, side: 'C', actionId: 3, label: 'Video Review', emoji: 'V', isHit: false, reviewOutcome: 'overturned:annulled' },
      ];
      const simple = {
        at10: (window as any).visibleOverlayEvent(10.5, 0, false)?.ev?.side,
        windowEnd: (window as any).visibleOverlayEvent(12.6, 0, false), // past the 2.5s pill window
      };
      // Clock parked at bout 10 from video 12s to 30s (a review interaction).
      // The pill must show ONCE at its video anchor, not again throughout the
      // parked span where bout time stays inside the window.
      M.recordingPauses = [{ startVideo: 12, endVideo: 30, boutTime: 10 }];
      const parked = {
        atAnchor: (window as any).visibleOverlayEvent(11, 0, true)?.ev?.side,
        duringParkedSpan: (window as any).visibleOverlayEvent(20, 0, true),
      };
      M.recordingPauses = [];
      return { simple, parked };
    });

    expect(result.simple.at10).toBe('R'); // the fencer action wins, not the referee event
    expect(result.simple.windowEnd).toBeNull();
    expect(result.parked.atAnchor).toBe('R');
    expect(result.parked.duringParkedSpan).toBeNull(); // no second pill while the clock is parked
  });

  test('review payload carries progress over reviewable events', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const payload = await page.evaluate(() => {
      (window as any).__reviewPayload = null;
      (window as any).AndroidVideo = {
        startVideoReview(json: string) { (window as any).__reviewPayload = JSON.parse(json); },
      };
      _nativeImportVideo = { uri: 'content://review/source.mp4', durationMs: 30000 };
      M.events = [
        { ts: 8, period: 1, side: 'L', actionId: 100, label: 'Simple Attack', emoji: 'A', isHit: true, reviewed: true },
        { ts: 12, period: 1, side: 'R', actionId: 200, label: 'Simple Attack', emoji: 'A', isHit: true },
        { ts: 12, period: 1, side: 'C', actionId: 3, label: 'Video Review', emoji: 'V', isHit: false },
      ];
      (window as any).startVideoReviewForIndex(1);
      return (window as any).__reviewPayload;
    });

    expect(payload.progressText).toBe('Event 2 of 2 - 1 reviewed');
  });

  test('malformed recording pauses are dropped from export payloads', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      const good = { startVideo: 5, endVideo: 8, boutTime: 4 };
      const inverted = { startVideo: 9, endVideo: 2, boutTime: 6 };
      const unfinished = { startVideo: 11, boutTime: 10 };
      M.recordingPauses = [good, inverted, unfinished];
      const payload = (window as any).nativeOverlayPayload('recorded', 'content://recorded/x.mp4', {
        segments: [{ uri: 'content://recorded/x.mp4', durationMs: 10000, startBoutTs: 0, pauses: [inverted, good, unfinished] }],
      });
      return {
        matchPauses: payload.match.recordingPauses,
        segmentPauses: payload.segments[0].pauses,
      };
    });

    expect(result.matchPauses).toEqual([{ startVideo: 5, endVideo: 8, boutTime: 4 }]);
    expect(result.segmentPauses).toEqual([{ startVideo: 5, endVideo: 8, boutTime: 4 }]);
  });
});

test.describe('imported-video playback controls', () => {
  const installImportMocks = async (page: Page) => {
    await page.evaluate(() => {
      (window as any).__calls = { play: 0, pause: 0, seeks: [] as number[], speeds: [] as number[] };
      (window as any).AndroidVideo = {
        exportOverlay() {},
        closeVideoReview() {},
        clearImportedPreview() {},
        startVideoReview() {},
        playImportedPreview() { (window as any).__calls.play++; },
        pauseImportedPreview() { (window as any).__calls.pause++; },
        seekImportedPreview(sec: number) { (window as any).__calls.seeks.push(sec); },
        setImportedPreviewSpeed(rate: number) { (window as any).__calls.speeds.push(rate); },
      };
      _nativeImportVideo = { uri: 'content://review/source.mp4', durationMs: 120000 };
      _importVideoFile = { name: 'source.mp4', nativeUri: 'content://review/source.mp4' } as any;
    });
  };

  test('starting a match with an imported video shows the control strip and auto-plays', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await installImportMocks(page);
    await startMatch(page);

    const result = await page.evaluate(() => ({
      stripVisible: document.getElementById('import-strip')?.classList.contains('on'),
      running: M.running,
      playCalls: (window as any).__calls.play,
      countdownActive: document.getElementById('s-countdown')?.classList.contains('active'),
      speeds: (window as any).__calls.speeds,
    }));

    expect(result.stripVisible).toBe(true);
    expect(result.running).toBe(true);
    expect(result.playCalls).toBeGreaterThan(0);
    expect(result.countdownActive).toBe(false);
    expect(result.speeds).toContain(1);
  });

  test('playback progress drives the bout clock and event stamping', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await installImportMocks(page);
    await startMatch(page);

    const result = await page.evaluate(() => {
      (window as any).onAndroidImportedPreviewProgress(JSON.stringify({
        positionMs: 42500,
        durationMs: 120000,
        playing: true,
      }));
      const timerAfterProgress = M.timerSec;
      (window as any).doConfirm('L', 100, true);
      return {
        timerAfterProgress,
        event: M.events[0],
        timeLabel: document.getElementById('is-time')?.textContent,
      };
    });

    expect(result.timerAfterProgress).toBeCloseTo(180 - 42.5, 3);
    expect(result.event.ts).toBeCloseTo(42.5, 3);
    expect(result.event.period).toBe(1);
    expect(result.timeLabel).toBe('0:42 / 2:00');
  });

  test('opening the radial pauses playback and confirming resumes it', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await installImportMocks(page);
    await startMatch(page);

    const result = await page.evaluate(() => {
      const before = { ...(window as any).__calls };
      (window as any).openPie('L');
      const pausedAfterPie = !M.running;
      const pauseCalls = (window as any).__calls.pause - before.pause;
      (window as any).closePie('L', false);
      (window as any).doConfirm('L', 100, true);
      return {
        pausedAfterPie,
        pauseCalls,
        runningAfterConfirm: M.running,
        playAfterConfirm: (window as any).__calls.play - before.play,
      };
    });

    expect(result.pausedAfterPie).toBe(true);
    expect(result.pauseCalls).toBeGreaterThan(0);
    expect(result.runningAfterConfirm).toBe(true);
    expect(result.playAfterConfirm).toBeGreaterThan(0);
  });

  test('speed buttons set the playback rate on the backend', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await installImportMocks(page);
    await startMatch(page);

    const result = await page.evaluate(() => {
      document.getElementById('is-speed-05')?.dispatchEvent(new Event('touchend', { bubbles: true, cancelable: true }));
      const halfActive = document.getElementById('is-speed-05')?.classList.contains('on');
      document.getElementById('is-speed-025')?.dispatchEvent(new Event('touchend', { bubbles: true, cancelable: true }));
      return {
        halfActive,
        speeds: (window as any).__calls.speeds,
        quarterActive: document.getElementById('is-speed-025')?.classList.contains('on'),
      };
    });

    expect(result.halfActive).toBe(true);
    expect(result.quarterActive).toBe(true);
    expect(result.speeds).toContain(0.5);
    expect(result.speeds).toContain(0.25);
  });

  test('seeking moves the clock without recording events', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await installImportMocks(page);
    await startMatch(page);

    const result = await page.evaluate(() => {
      (window as any).onAndroidImportedPreviewProgress(JSON.stringify({
        positionMs: 10000, durationMs: 120000, playing: true,
      }));
      (window as any).importSeek(65);
      return {
        seeks: (window as any).__calls.seeks,
        timerSec: M.timerSec,
        events: M.events.length,
      };
    });

    expect(result.seeks).toContain(65);
    expect(result.timerSec).toBeCloseTo(180 - 65, 3);
    expect(result.events).toBe(0);
  });

  test('review navigation and events list follow bout time for out-of-order events', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await installImportMocks(page);
    await startMatch(page);

    const result = await page.evaluate(() => {
      // Events annotated out of order: 30s, then back at 8s, then 50s.
      M.events = [
        { ts: 30, period: 1, side: 'R', actionId: 200, label: 'Simple Attack', emoji: 'A', isHit: true },
        { ts: 8, period: 1, side: 'L', actionId: 100, label: 'Simple Attack', emoji: 'A', isHit: true },
        { ts: 50, period: 1, side: 'L', actionId: 101, label: 'Compound', emoji: 'C', isHit: false },
      ];
      (window as any).renderEventsList();
      const listTimes = Array.from(document.querySelectorAll('#ev-list .ev-ts')).map(el => el.textContent);
      return {
        listTimes,
        nextAfter8: (window as any).adjacentReviewIndex(1, 1),
        prevBefore30: (window as any).adjacentReviewIndex(0, -1),
        nextAfter50: (window as any).adjacentReviewIndex(2, 1),
      };
    });

    expect(result.listTimes).toEqual(['0:08', '0:30', '0:50']);
    expect(result.nextAfter8).toBe(0);
    expect(result.prevBefore30).toBe(1);
    expect(result.nextAfter50).toBe(-1);
  });
});

test.describe('radial menu OTHER sector label', () => {
  test('MAIN sectors expose OTHER and not NONE for the no-right-of-way slot', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      const labels: string[] = RADIAL_DEFS.MAIN.sectors.map((s: any) => s.label);
      return { labels };
    });

    expect(result.labels).toContain('OTHER');
    expect(result.labels).not.toContain('NONE');
  });

  test('breadcrumb shows OTHER when drilling into the no-right-of-way branch', async ({ page }) => {
    await page.goto(ANDROID_APP_PATH);
    await startMatch(page);

    const result = await page.evaluate(() => {
      (window as any).openPie('L');
      (window as any)._radTransitionTo('NONE', true);
      const top = document.getElementById('r-center-top')?.textContent ?? '';
      (window as any).closePie('L', false);
      return { top };
    });

    // Center top shows "<side> · <menu label>", e.g. "L · OTHER"
    expect(result.top).toContain('OTHER');
    expect(result.top).not.toContain('NONE');
  });
});

