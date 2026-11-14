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
        lv.setAdapter(new ArrayAdapter<>(this, R.layout.item_app, labels));
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Prefs.setTarget(AppPickerActivity.this, pkgs.get(position));
                finish();
            }
        });
        lv.requestFocus();
    }
}
