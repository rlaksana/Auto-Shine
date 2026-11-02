package com.mine.autoshine;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;

import android.os.Handler;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.os.IBinder;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.view.Display;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

public class ShineControl implements SensorEventListener {
	private final SensorManager sMgr;
	private final Sensor lightSensor;
	private final MySettings sett;
	private final ContentResolver cResolver;

	private final Context mContext;
	private final TelephonyManager telephonyManager;
	private final int bMin, bMax;

	private final Handler delayer = new Handler();

	private final long pause = 1000;
	private final long poll = 10000;

	private boolean onListen = false;
	private boolean landscape = false;

	private float lux = 0;
	private int tempBrightness = 0;
	private int lastWritten = -1;
	private int pendingBrightness = -1;
	private long writeDelay = 1000;
	private int retries = 0;
	private final Runnable resumeListening = new ResumeListening();
	private final Runnable writer = new Writer();

	ShineControl(Context context) {
		sett = new MySettings(context);
		// Pre-warm the Shizuku user service: the first spawn takes ~5s, and writes
		// that arrive before it completes would fall back to an overwritten putInt.
		if (sett.companion)
			ensureUserService();
		cResolver = context.getContentResolver();
		sMgr = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
		lightSensor = sMgr.getDefaultSensor(Sensor.TYPE_LIGHT);

		mContext = context;
		telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

		// Start from the device's own config resources.
		Resources sysRes = Resources.getSystem();
		int minId = sysRes.getIdentifier("config_screenBrightnessSettingMinimum", "integer", "android");
		int maxId = sysRes.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android");
		int min = minId > 0 ? sysRes.getInteger(minId) : 1;
		int max = maxId > 0 ? sysRes.getInteger(maxId) : 255;

		// ColorOS 17 (OPPO) lies in its config: it actually keeps SCREEN_BRIGHTNESS in a
		// 2..4093 linear scale (12-bit backlight). Verified on device: a third-party app
		// writes 626 for a 15% preset and the framework accepts it as-is. Do NOT trust
		// PowerManager.get*ScreenBrightnessSetting() either: MIUI reports 7..16383 from it
		// while its settings scale stays 0..255, and writing out-of-range pegs the screen.
		String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toUpperCase();
		if (manufacturer.contains("OPPO") || manufacturer.contains("OPLUS")) {
			min = 2;
			max = 4093;
		}
		bMin = min;
		bMax = max;

		int current = 0;
		try {
			current = Settings.System.getInt(cResolver, Settings.System.SCREEN_BRIGHTNESS, 0);
		} catch (Exception ignored) {}
		Log.d("ShineControl", "brightness range: " + bMin + ".." + bMax + ", current=" + current);
	}

	@Override
	public void onAccuracyChanged(Sensor arg0, int arg1) {
		// TODO Auto-generated method stub
	}

	// True when the physical display is off even while the device stays awake:
	// scrcpy's turn-screen-off keeps the system interactive and sends no
	// ACTION_SCREEN_OFF, so neither stopListening() nor isInteractive can see it.
	private boolean isDisplayPhysicallyOff() {
		Display d = ((DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE))
				.getDisplay(Display.DEFAULT_DISPLAY);
		return d != null && d.getState() != Display.STATE_ON;
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
			// Companion mode polls: any ongoing registration makes ColorOS override
			// even other apps' writes, so sample one event, unregister at once, and
			// let the timed re-register start the next cycle.
			if (sett.companion && onListen) {
				sMgr.unregisterListener(this);
				onListen = false;
				delayer.postDelayed(resumeListening, poll);
			}

			// Display off -> no light capture, no brightness adjust. Sampling resumes
			// on the next event/poll once the display is on again, no state needed.
			if (isDisplayPhysicallyOff())
				return;

			// Oppo "Fusion Light" sensors pack lux in values[6] and keep values[0] at 0
			lux = (event.sensor.getName().contains("Fusion") && event.values.length > 6)
					? event.values[6] : event.values[0];

			setBrightness((int) lux);
		}
	}

	private boolean shouldSuspend() {
		if (sett.mode == Constants.WORK_MODE_ALWAYS)
			return false;

		// UNLOCK stays active while the screen is on; screen off already stops listening
		if (sett.mode == Constants.WORK_MODE_UNLOCK)
			return false;

		if (sett.mode == Constants.WORK_MODE_LANDSCAPE && landscape)
			return false;

		if (sett.mode == Constants.WORK_MODE_PORTRAIT && !landscape)
			return false;

		return true;
	}

	private void scheduleSuspend() {
		if (shouldSuspend())
			delayer.postDelayed(new Runner(), pause);
	}

	public int getState() {
		if (!shouldSuspend())
			if (onListen)
				return Constants.SERVICE_STATE_ACTIVE;
			else
				return Constants.SERVICE_STATE_PAUSED;

		return Constants.SERVICE_STATE_SUSPENDED;
	}

	private class Runner implements Runnable {
		public void run() {
			boolean ringing = false;
			if (mContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
				int state = telephonyManager.getCallState();
				ringing = (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK);
			}

			if (ringing) {
				delayer.postDelayed(new Runner(), pause * 2);
			} else {
				stopListening();
			}

		}
	}

	public void startListening(boolean onRotate) {
		if ((sett.mode == Constants.WORK_MODE_UNLOCK) && !sett.rotate && onRotate)
			return;

		if (!onListen) {
			sMgr.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
			onListen = true;
		}

		scheduleSuspend();
	}

	public void stopListening() {
		if (onListen)
			sMgr.unregisterListener(this);

		delayer.removeCallbacksAndMessages(null);
		pendingBrightness = -1;
		onListen = false;
	}

	private void setBrightness(int lux) {
		float x1, y1, x2, y2;
		int brightness;
		if (lux <= sett.l1)
			brightness = sett.b1;
		else if (lux > sett.l4)
			brightness = sett.b4;
		else {
			x1 = sett.l3;
			x2 = sett.l4;
			y1 = sett.b3;
			y2 = sett.b4;

			if (lux <= sett.l2) {
				x1 = sett.l1;
				x2 = sett.l2;
				y1 = sett.b1;
				y2 = sett.b2;
			} else if (lux <= sett.l3) {
				x1 = sett.l2;
				x2 = sett.l3;
				y1 = sett.b2;
				y2 = sett.b3;
			}

			float div = lux - x1;
			float xlen = x2 - x1;
			float ylen = y2 - y1;
			float coef = div / xlen;
			brightness = (int) (y1 + ylen * coef);
		}

		// Scale the app's 1-255 curve to the device's own settings range
		brightness = bMin + (brightness - 1) * (bMax - bMin) / 254;
		tempBrightness = brightness;

		// Skip when the target barely moved past the threshold.
		if (lastWritten != -1 && Math.abs(brightness - lastWritten) <= sett.threshold)
			return;

		// Coalesce and delay the write so it lands AFTER the one-shot enforcement burst
		// ColorOS fires ~660ms after the light sensor registration (only the first
		// registration per process triggers it). Rapid target changes are folded into
		// a single write.
		// Coalesce: events during the delay window only refresh the target. Schedule
		// only when nothing is pending, or a 5Hz sensor would cancel the write forever.
		boolean wasPending = pendingBrightness != -1;
		pendingBrightness = brightness;
		if (!wasPending) {
			// Fresh attempt: reset the bind-wait/recover ladders so a previous
			// give-up cannot make every later attempt quit instantly.
			bindWaits = 0;
			bindRecovers = 0;
			delayer.postDelayed(writer, writeDelay);
			Log.i("ShineControl", "scheduled write " + brightness + " in " + writeDelay + "ms");
		}
	}

	private class ResumeListening implements Runnable {
		public void run() {
			startListening(false);
		}
	}

	private boolean shizukuAvailable() {
		try {
			if (Shizuku.pingBinder())
				return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
			// Binder not delivered to this (non-provider) process yet — re-request it.
			// The registerReceiver inside is repeated per call; bounded by retry caps.
			ShizukuProvider.requestBinderForNonProviderProcess(mContext);
			return false;
		} catch (Throwable t) {
			return false;
		}
	}

	// Shizuku#newProcess is private in the current API — the blessed replacement is a
	// user service (see the API README): code of ours runs in a shell-UID process.
	private IBrightnessService brightnessService;
	private boolean serviceBinding;
	private int bindWaits = 0;
	private int bindRecovers = 0;
	private final Shizuku.UserServiceArgs serviceArgs = new Shizuku.UserServiceArgs(
			new ComponentName("com.mine.autoshine", BrightnessService.class.getName()))
			.daemon(false)
			.processNameSuffix("brightness")
			.version(2); // bump to redeploy BrightnessService

	private synchronized void ensureUserService() {
		if (brightnessService != null || serviceBinding)
			return;
		try {
			if (Shizuku.getVersion() < 10)
				return;
			serviceBinding = true;
			Shizuku.bindUserService(serviceArgs, new ServiceConnection() {
				@Override
				public void onServiceConnected(ComponentName name, IBinder binder) {
					brightnessService = IBrightnessService.Stub.asInterface(binder);
					serviceBinding = false;
				}

				@Override
				public void onServiceDisconnected(ComponentName name) {
					brightnessService = null;
				}
			});
		} catch (Throwable t) {
			serviceBinding = false;
			Log.e("ShineControl", "bind user service failed", t);
		}
	}

	// The server may keep a wedged user-service record after the app was force-stopped
	// (its shell-UID process survives force-stop); dropping the record lets a fresh
	// service spawn.
	private synchronized void recoverUserService() {
		try {
			Shizuku.unbindUserService(serviceArgs, null, true);
		} catch (Throwable t) {
			Log.e("ShineControl", "unbind user service failed", t);
		}
		serviceBinding = false;
	}

	private String shizukuRun(String[] cmd) throws Exception {
		ensureUserService();
		IBrightnessService s = brightnessService;
		if (s == null)
			throw new IllegalStateException("shizuku user service not bound yet");
		return s.run(cmd);
	}

	private class Writer implements Runnable {
		public void run() {
			if (pendingBrightness == -1)
				return;
			// In-flight race: this write was scheduled while the display was on and
			// may now execute against a physically-off display, where ColorOS applies
			// it by lighting the backlight. Drop without touching lastWritten, so the
			// next event reschedules once the display is on again.
			if (isDisplayPhysicallyOff()) {
				Log.i("ShineControl", "display off, drop write " + pendingBrightness);
				pendingBrightness = -1;
				return;
			}
			int value = pendingBrightness;
			pendingBrightness = -1;
			// lastWritten is set on verified success only — setting it here would make
			// a failed write poison later identical targets through the threshold check.

			if (sett.companion) {
				// The sampling window is already closed (see onSensorChanged), so with no
				// sensor holder ColorOS adopts this write. Shizuku performs it with shell
				// UID — the write class ColorOS adopts (verified: provider AND backlight
				// follow) — and "settings get" returns the true provider value while
				// getInt is masked for apps on ColorOS.
				new Thread(new Runnable() {
					public void run() {
						if (!shizukuAvailable()) {
							// Binder not delivered yet — retry briefly before falling back
							// to an app-context putInt that ColorOS overwrites anyway.
							if (retries < 2) {
								retries++;
								pendingBrightness = value;
								delayer.postDelayed(writer, 500);
								return;
							}
							Settings.System.putInt(cResolver, Settings.System.SCREEN_BRIGHTNESS, value);
							lastWritten = value;
							Log.i("ShineControl", "shizuku not ready, fallback putInt " + value);
							return;
						}
						try {
							shizukuRun(new String[]{"settings", "put", "system", "screen_brightness", String.valueOf(value)});
							SystemClock.sleep(500);
							String now = shizukuRun(new String[]{"settings", "get", "system", "screen_brightness"}).trim();
							Log.i("ShineControl", "shizuku verify: now=" + now + " written=" + value);
							if (now.equals(String.valueOf(value))) {
								// Verified success — only now may the threshold gate
								// treat this target as already applied.
								try {
									// ColorOS can hold the physical backlight while the provider
									// accepts writes — a dark screen with a verified write is that
									// hold, not a lost write. Sample physical once so logs tell
									// the two apart.
									String phys = shizukuRun(new String[]{"/system/bin/sh", "-c",
											"dumpsys display | grep -o 'mBrightnessState=[0-9]*' | head -1"}).trim();
									if (!phys.isEmpty() && !phys.endsWith(String.valueOf(value)))
										Log.w("ShineControl", "physical " + phys + " != written " + value + " (ColorOS hold?)");
								} catch (Throwable ignored) {}
								lastWritten = value;
								retries = 0;
								bindWaits = 0;
								bindRecovers = 0;
								return;
							}
						} catch (Throwable t) {
							if (brightnessService == null && bindWaits < 10) {
								// UserService spawn takes seconds — wait politely with the
								// same target instead of burning write retries on it.
								bindWaits++;
								Log.i("ShineControl", "user service not bound yet, wait " + bindWaits);
								pendingBrightness = value;
								delayer.postDelayed(writer, 1000);
								return;
							}
							if (brightnessService == null) {
								if (bindRecovers < 2) {
									Log.e("ShineControl", "user service not bound, dropping record");
									bindWaits = 0;
									bindRecovers++;
									pendingBrightness = value;
									recoverUserService();
									delayer.postDelayed(writer, 2000);
									return;
								}
								Log.i("ShineControl", "shizuku user service unavailable, stop retrying this target");
								// Unpoison: the next poll cycle may retry this target once
								// the user service has finished spawning.
								lastWritten = -1;
								pendingBrightness = -1;
								return;
							}
							Log.e("ShineControl", "shizuku write failed", t);
						}
						if (retries < 2) {
							retries++;
							if (writeDelay < 8000)
								writeDelay *= 2;
							pendingBrightness = value;
							delayer.postDelayed(writer, writeDelay);
						} else {
							Log.i("ShineControl", "shizuku write rejected 3x, stop retrying this target");
							// Record the abandoned target so later identical samples
							// stay silent instead of restarting the rejected ladder.
							lastWritten = value;
						}
					}
				}).start();
				return;
			}

			boolean ok = Settings.System.putInt(cResolver, Settings.System.SCREEN_BRIGHTNESS, value);
			lastWritten = value;
			Log.i("ShineControl", "putInt " + value + " ok=" + ok);

			// Verify the write survived; if ColorOS snapped it back, back off and retry
			// the same target later.
			delayer.postDelayed(new Runnable() {
				public void run() {
					int now = Settings.System.getInt(cResolver, Settings.System.SCREEN_BRIGHTNESS, -1);
					Log.i("ShineControl", "verify: now=" + now + " written=" + lastWritten);
					if (now != lastWritten) {
						// Cap the retries: a persistent enforcer would otherwise turn this
						// into an endless write flood. A target move past the threshold
						// restarts the whole 1s->2s->4s ladder from scratch.
						if (retries >= 2) {
							Log.i("ShineControl", "write rejected 3x, stop retrying this target");
							pendingBrightness = -1;
							return;
						}
						retries++;
						if (writeDelay < 8000)
							writeDelay *= 2;
						pendingBrightness = lastWritten;
						delayer.postDelayed(writer, writeDelay);
					} else {
						retries = 0;
					}
				}
			}, 500);
		}
	}

	public void reconfigure() {
		stopListening();
		sett.load();
		startListening(false);
	}

	public void setLandscape(boolean land) {
		landscape = land;
	}

	public void onScreenUnlock() {
		Settings.System.putInt(cResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
				Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);

		startListening(false);
	}

	public int getLastSensorValue() {
		return (int) lux;
	}

	public int getSetBrightness() {
		return tempBrightness;
	}

	public boolean getOnListen() {
		return onListen;
	}
}
