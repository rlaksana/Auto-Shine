package com.mine.autoshine.companion;

import android.app.Activity;
import android.os.Bundle;

// ColorOS blocks waking a never-run app by broadcast/activity from the background;
// opening this activity once after install lifts the app out of the stopped state.
public class MainActivity extends Activity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		finish();
	}
}
