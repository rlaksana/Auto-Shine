package com.mine.autoshine;

import java.io.BufferedReader;
import java.io.InputStreamReader;

// Runs inside a Shizuku user service process (shell UID), so Runtime.exec here
// has the same power as adb shell — the write class ColorOS adopts.
public class BrightnessService extends IBrightnessService.Stub {
    @Override
    public String run(String[] cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null)
                out.append(line);
            r.close();
            p.waitFor();
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
