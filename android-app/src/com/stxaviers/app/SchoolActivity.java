package com.stxaviers.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * SCHOOL (v1.1.0) — identity + contacts, from school-info.md (the
 * researched source of truth). Call / mail / map rows use the system
 * dialer / mailer / maps.
 */
public class SchoolActivity extends XdActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_school);

        findViewById(R.id.sc_back).setOnClickListener(v -> finish());

        findViewById(R.id.sc_call).setOnClickListener(v -> open(
                "tel:+919835061341"));
        findViewById(R.id.sc_mail).setOnClickListener(v -> open(
                "mailto:helpdesk@stxaviers.org"));
        findViewById(R.id.sc_map).setOnClickListener(v -> open(
                "https://maps.google.com/?q=St.+Xavier's+Jr.+Sr.+School,"
                        + "+Goshala+Road,+Muzaffarpur,+Bihar+842002"));
    }

    private void open(String uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            startActivity(i);
        } catch (Throwable t) {
            Ui.toast(this, getString(R.string.no_viewer));
        }
    }
}
