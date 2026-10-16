package org.parallelnest.probe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** The same Activity runs as a normal installed app and from an unchanged copied APK. */
public class GuestProbeActivity extends Activity {
    private SharedPreferences prefs;
    private void event(String value) {
        prefs.edit().putString("lifecycle", prefs.getString("lifecycle", "") + value + ";").commit();
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("guest_probe", MODE_PRIVATE);
        event("create");
        // First-party crash-recovery fixture; the marker is private to the selected guest.
        File crashOnce = new File(getFilesDir(), "crash-once");
        if (crashOnce.exists()) {
            if (!crashOnce.delete()) throw new IllegalStateException("Cannot consume crash marker");
            throw new IllegalStateException("Intentional first-party guest startup failure");
        }
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(24, 56, 24, 24);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(column);
        TextView title = new TextView(this);
        title.setText("Actual Guest Probe Activity"); title.setTextSize(24); column.addView(title);
        TextView identity = new TextView(this);
        identity.setText("Package: " + getPackageName() + "\nUID: " + Process.myUid() + " PID: " + Process.myPid() + "\nData: " + getFilesDir().getParent());
        column.addView(identity);
        EditText value = new EditText(this);
        value.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        value.setHint("Independent persistent value");
        value.setSingleLine(true);
        value.setText(prefs.getString("value", "")); column.addView(value);
        TextView result = new TextView(this);
        result.setText("Saved: " + prefs.getString("value", "(empty)"));
        Button save = new Button(this); save.setText("Save guest value"); column.addView(save); column.addView(result);
        save.setOnClickListener(view -> {
            String text = value.getText().toString();
            try {
                if (!prefs.edit().putString("value", text).commit()) throw new IOException("Preferences commit failed");
                try (FileOutputStream out = openFileOutput("value.txt", MODE_PRIVATE)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
                try (SQLiteDatabase db = openOrCreateDatabase("probe.db", MODE_PRIVATE, null)) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS probe (id INTEGER PRIMARY KEY, value TEXT)");
                    db.execSQL("INSERT OR REPLACE INTO probe(id,value) VALUES(1,?)", new Object[]{text});
                }
                result.setText("Saved: " + text);
            } catch (Exception e) { result.setText("Save failed: " + e.getMessage()); }
        });
        TextView limits = new TextView(this);
        limits.setText("First-party Activity hosting prototype. Files, preferences and SQLite are separate per instance. All guests share the host Android UID. Services, providers, jobs and hardware bridges are unsupported."); column.addView(limits);
        setContentView(scroll);
    }
    @Override protected void onStart() { super.onStart(); event("start"); }
    @Override protected void onResume() { super.onResume(); event("resume"); }
    @Override protected void onPause() { event("pause"); super.onPause(); }
    @Override protected void onStop() { event("stop"); super.onStop(); }
    @Override protected void onDestroy() { event("destroy"); super.onDestroy(); }
}
