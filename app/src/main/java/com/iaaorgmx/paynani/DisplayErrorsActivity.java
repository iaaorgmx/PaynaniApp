package com.iaaorgmx.paynani;
//PaynaniApp
import android.app.Activity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

public class DisplayErrorsActivity extends Activity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setTitle(getString(R.string.app_name));
		
		setContentView(R.layout.activity_display_errors);
		
		Bundle params = getIntent().getExtras();
		
		TextView errors = (TextView) findViewById(R.id.error_report);
		
		errors.setText(params.getString("report"));
	}


}
