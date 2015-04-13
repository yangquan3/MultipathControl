/*
 * This file is part of MultipathControl.
 *
 * Copyright 2012 UCLouvain - Gregory Detal <first.last@uclouvain.be>
 * Copyright 2015 UCLouvain - Matthieu Baerts <first.last@student.uclouvain.be>
 *
 * MultipathControl is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package be.uclouvain.multipathcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import be.uclouvain.multipathcontrol.global.Config;
import be.uclouvain.multipathcontrol.global.Manager;
import be.uclouvain.multipathcontrol.ifaces.IPRoute;
import be.uclouvain.multipathcontrol.ifaces.MobileDataMgr;
import be.uclouvain.multipathcontrol.stats.JSONSender;
import be.uclouvain.multipathcontrol.stats.SaveDataApp;
import be.uclouvain.multipathcontrol.stats.SaveDataHandover;
import be.uclouvain.multipathcontrol.system.IPRouteUtils;
import be.uclouvain.multipathcontrol.ui.Notifications;

public class MPCtrl {

	private final Context context;
	private final Notifications notif;
	private final MobileDataMgr mobileDataMgr;
	private final Handler handler;
	private final IPRoute iproute;
	private static long lastTimeHandler;

	private BroadcastReceiver mConnReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			Log.i(Manager.TAG, "BroadcastReceiver " + intent);
			PowerManager pm = (PowerManager) context
					.getSystemService(Context.POWER_SERVICE);
			if (pm.isScreenOn())
				mobileDataMgr.setMobileDataActive(Config.mEnabled);
			if (iproute.monitorInterfaces())
				new SaveDataHandover(context);
		}
	};

	public MPCtrl(Context context) {
		this.context = context;
		Config.getDefaultConfig(context);
		notif = new Notifications(context);
		mobileDataMgr = new MobileDataMgr(context);
		iproute = new IPRoute(mobileDataMgr);
		Log.i(Manager.TAG, "new MPCtrl");

		handler = new Handler();
		initHandler();

		/*
		 * mConnReceiver will be called each time a change of connectivity
		 * happen
		 */
		context.registerReceiver(mConnReceiver, new IntentFilter(
				ConnectivityManager.CONNECTIVITY_ACTION));

		if (Config.mEnabled)
			notif.showNotification();

		// Log
		new SaveDataApp(context);
		new SaveDataHandover(context);
	}

	public void destroy() {
		try {
			context.unregisterReceiver(mConnReceiver);
		} catch (IllegalArgumentException e) {
		}

		Log.i(Manager.TAG, "destroy MPCtrl");

		handler.getLooper().quit();

		notif.hideNotification();
	}

	public boolean setStatus(boolean isChecked) {
		if (isChecked == Config.mEnabled)
			return false;

		Log.i(Manager.TAG, "set new status "
				+ (isChecked ? "enable" : "disable"));
		Config.mEnabled = isChecked;
		Config.saveStatus(context);

		if (isChecked) {
			notif.showNotification();
			if (iproute.monitorInterfaces())
				new SaveDataHandover(context);
		} else {
			notif.hideNotification();
		}

		return true;
	}

	public boolean setDefaultData(boolean isChecked) {
		if (isChecked == Config.defaultRouteData)
			return false;
		Config.defaultRouteData = isChecked;
		Config.saveStatus(context);

		return IPRouteUtils.setDefaultRoute();
	}

	public boolean setDataBackup(boolean isChecked) {
		if (isChecked == Config.dataBackup)
			return false;
		Config.dataBackup = isChecked;
		Config.saveStatus(context);

		return IPRouteUtils.setDataBackup();
	}

	public boolean setSaveBattery(boolean isChecked) {
		if (isChecked == Config.saveBattery)
			return false;
		Config.saveBattery = isChecked;
		Config.saveStatus(context);
		return true; // nothing to do here: we need to restart app
	}

	public boolean setSavePowerGPS(boolean isChecked) {
		if (isChecked == Config.savePowerGPS)
			return false;
		Config.savePowerGPS = isChecked;
		Config.saveStatus(context);
		SaveDataHandover.savePowerGPS(isChecked);
		return true;
	}

	// Will not be executed in deep sleep, nice, no need to use both connections
	// in deep-sleep
	private void initHandler() {
		lastTimeHandler = System.currentTimeMillis();

		// First check
		handler.post(runnableSetMobileDataActive);
		handler.postDelayed(runnableSendData, 5 * 1000 * 60); // 5min
	}

	/*
	 * Ensures that the data interface and WiFi are connected at the same time.
	 */
	private Runnable runnableSetMobileDataActive = new Runnable() {
		final long fiveSecondsMs = 5 * 1000;

		@Override
		public void run() {
			long nowTime = System.currentTimeMillis();
			// do not try keep mobile data active in deep sleep mode
			if (Config.mEnabled
					&& nowTime - lastTimeHandler < fiveSecondsMs * 2)
				// to not disable cellular iface
				mobileDataMgr.setMobileDataActive(Config.mEnabled);
			lastTimeHandler = nowTime;
			handler.postDelayed(this, fiveSecondsMs);
		}
	};

	private Runnable runnableSendData = new Runnable() {
		final long oneHourMs = 1000 * 60 * 60;

		@Override
		public void run() {
			Log.d(Manager.TAG, "Schedule: new upload");
			JSONSender.sendAll(context);
			handler.postDelayed(this, oneHourMs);
		}
	};
}
