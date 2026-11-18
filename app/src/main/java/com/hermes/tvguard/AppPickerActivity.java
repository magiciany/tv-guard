package com.hermes.tvguard;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 从已安装的可启动 App 中选择目标。 */
public class AppPickerActivity extends Activity {
    private static final int REQ_PARENT = 21;
    private boolean authenticated;
    private boolean waitingForPin;
    private ListView apps;

    @Override
    protected void onResume() {
        super.onResume();
        GuardService service = GuardService.INSTANCE;
        if (service != null) service.pauseForSettings();
        if (isFinishing()) return;
        if (!PinStore.hasPin(this)) authenticated = true;
        apps.setVisibility(authenticated ? View.VISIBLE : View.INVISIBLE);
        apps.setEnabled(authenticated);
        if (!authenticated && !waitingForPin) {
            waitingForPin = true;
            startActivityForResult(new Intent(this, PinActivity.class)
                    .putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_VERIFY), REQ_PARENT);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Auth lasts only until this picker leaves the foreground; never save it in a Bundle.
        authenticated = false;
        apps.setEnabled(false);
        apps.setVisibility(View.INVISIBLE);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_PARENT) {
            boolean expected = waitingForPin;
            waitingForPin = false;
            if (!expected || result != RESULT_OK) { finish(); return; }
            authenticated = true;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picker);

        PackageManager pm = getPackageManager();
        List<ResolveInfo> all = new ArrayList<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Intent leanback = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        try {
            all.addAll(pm.queryIntentActivities(launcher, 0));
        } catch (Exception ignored) {
        }
        try {
            all.addAll(pm.queryIntentActivities(leanback, 0));
        } catch (Exception ignored) {
        }

        Set<String> seen = new HashSet<>();
        final List<String> pkgs = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        for (ResolveInfo ri : all) {
            if (ri.activityInfo == null) {
                continue;
            }
            String p = ri.activityInfo.packageName;
            if (p == null || p.equals(getPackageName()) || !seen.add(p)) {
                continue;
            }
            String label;
            try {
                label = ri.loadLabel(pm).toString();
            } catch (Exception e) {
                label = p;
            }
            rows.add(new String[]{label, p});
        }
        Collections.sort(rows, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return a[0].compareToIgnoreCase(b[0]);
            }
        });

        final List<String> labels = new ArrayList<>();
        for (String[] r : rows) {
            labels.add(r[0] + "   [" + r[1] + "]");
            pkgs.add(r[1]);
        }

        ListView lv = findViewById(R.id.list_apps);
        apps = lv;
        apps.setEnabled(false);
        apps.setVisibility(View.INVISIBLE);
        lv.setAdapter(new ArrayAdapter<>(this, R.layout.item_app, labels));
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (!authenticated) return;
                authenticated = false; // Consume authorization with this selection.
                Prefs.setTarget(AppPickerActivity.this, pkgs.get(position));
                finish();
            }
        });
        lv.requestFocus();
    }
}
