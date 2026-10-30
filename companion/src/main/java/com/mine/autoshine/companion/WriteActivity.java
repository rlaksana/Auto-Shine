package com.mine.autoshine.companion;

import android.app.Activity;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

// Launched by Auto-Shine per write. ColorOS adopts brightness writes made while an
// activity is visible (Rogulin pattern) and overwrites background-receiver writes,
// so the write must happen at full focus, then the activity closes immediately.
public class WriteActivity extends Activity {

	private int value = -1;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		value = getIntent().getIntExtra("brightness", -1);
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (!hasFocus || value < 0 || value > 65535)
			return;

		try {
			Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value);
			Log.i("ShineCompanion", "foreground putInt " + value);
		} catch (SecurityException e) {
			// WRITE_SETTINGS appops not granted yet (resets on reinstall).
			Log.e("ShineCompanion", "write rejected, grant WRITE_SETTINGS via appops", e);
		}
		value = -1;
		finish();
	}
}
