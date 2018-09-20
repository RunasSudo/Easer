/*
 * Copyright (c) 2016 - 2018 Rui Zhao <renyuneyun@gmail.com>
 *
 * This file is part of Easer.
 *
 * Easer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Easer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Easer.  If not, see <http://www.gnu.org/licenses/>.
 */

package ryey.easer.core;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;

import com.orhanobut.logger.Logger;

import java.util.Collection;

import ryey.easer.commons.local_plugin.dynamics.DynamicsLink;
import ryey.easer.commons.local_plugin.dynamics.SolidDynamicsAssignment;
import ryey.easer.commons.local_plugin.operationplugin.Loader;
import ryey.easer.commons.local_plugin.operationplugin.OperationData;
import ryey.easer.commons.local_plugin.operationplugin.OperationPlugin;
import ryey.easer.core.data.ProfileStructure;
import ryey.easer.core.data.RemoteLocalOperationDataWrapper;
import ryey.easer.core.data.storage.ProfileDataStorage;
import ryey.easer.core.dynamics.CoreDynamics;
import ryey.easer.core.dynamics.CoreDynamicsInterface;
import ryey.easer.core.log.ActivityLogService;
import ryey.easer.plugins.PluginRegistry;
import ryey.easer.remote_plugin.RemoteOperationData;

import static ryey.easer.core.Lotus.EXTRA_DYNAMICS_LINK;
import static ryey.easer.core.Lotus.EXTRA_DYNAMICS_PROPERTIES;

public class ProfileLoaderService extends Service {
    public static final String ACTION_LOAD_PROFILE = "ryey.easer.action.LOAD_PROFILE";

    public static final String EXTRA_PROFILE_NAME = "ryey.easer.extra.PROFILE_NAME";
    public static final String EXTRA_SCRIPT_NAME = "ryey.easer.extra.EVENT_NAME";

    public static void triggerProfile(Context context, String profileName) {
        Intent intent = new Intent();
        intent.setAction(ProfileLoaderService.ACTION_LOAD_PROFILE);
        intent.putExtra(ProfileLoaderService.EXTRA_PROFILE_NAME, profileName);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }
    public static void triggerProfile(Context context, String profileName, String scriptName,
                                      Bundle dynamicsProperties, DynamicsLink dynamicsLink) {
        Intent intent = new Intent();
        intent.setAction(ProfileLoaderService.ACTION_LOAD_PROFILE);
        intent.putExtra(ProfileLoaderService.EXTRA_PROFILE_NAME, profileName);
        intent.putExtra(ProfileLoaderService.EXTRA_SCRIPT_NAME, scriptName);
        intent.putExtra(EXTRA_DYNAMICS_PROPERTIES, dynamicsProperties);
        intent.putExtra(EXTRA_DYNAMICS_LINK, dynamicsLink);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    private RemotePluginCommunicationHelper helper;

    private IntentFilter intentFilter = new IntentFilter(ACTION_LOAD_PROFILE);
    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (ACTION_LOAD_PROFILE.equals(action)) {
                final String name = intent.getStringExtra(EXTRA_PROFILE_NAME);
                final String event = intent.getStringExtra(EXTRA_SCRIPT_NAME);
                if (intent.getExtras() == null) {
                    Logger.wtf("ProfileLoaderIntent has null extras???");
                    throw new IllegalStateException("ProfileLoaderIntent has null extras???");
                }
                handleActionLoadProfile(name, event, intent.getExtras());
            } else {
                Logger.wtf("ProfileLoaderService got unknown Intent action <%s>", action);
            }
        }
    };

    @Override
    public void onCreate() {
        Logger.v("ProfileLoaderService onCreate()");
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, intentFilter);
        helper = new RemotePluginCommunicationHelper(this);
        helper.begin();
    }

    @Override
    public void onDestroy() {
        helper.end();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void handleActionLoadProfile(@NonNull String name, @Nullable String event, @NonNull Bundle extras) {
        Logger.d("Loading profile <%s> by <%s>", name, event);
        ProfileStructure profile;
        ProfileDataStorage storage = ProfileDataStorage.getInstance(this);
        profile = storage.get(name);

        DynamicsLink dynamicsLink = extras.getParcelable(EXTRA_DYNAMICS_LINK);
        Bundle macroData = extras.getBundle(EXTRA_DYNAMICS_PROPERTIES);
        if (dynamicsLink == null)
            dynamicsLink = new DynamicsLink();
        if (macroData == null)
            macroData = new Bundle();
        for (CoreDynamicsInterface dynamics : CoreDynamics.coreDynamics()) {
            if (dynamicsLink.identityMap().containsValue(dynamics.id())) {
                macroData.putString(dynamics.id(), dynamics.invoke(this, extras));
            }
        }
        final SolidDynamicsAssignment solidMacroAssignment = dynamicsLink.assign(macroData);

        if (profile != null) {
            boolean loaded = false;
            PluginRegistry.Registry<OperationPlugin, OperationData> operationRegistry = PluginRegistry.getInstance().operation();
            for (String pluginId : profile.pluginIds()) {
                Collection<RemoteLocalOperationDataWrapper> dataCollection = profile.get(pluginId);
                if (operationRegistry.hasPlugin(pluginId)) {
                    OperationPlugin plugin = operationRegistry.findPlugin(pluginId);
                    assert plugin != null;
                    Loader loader = plugin.loader(getApplicationContext());
                    for (RemoteLocalOperationDataWrapper data : dataCollection) {
                        OperationData localData = data.localData;
                        assert localData != null;
                        try {
                            //noinspection unchecked
                            if (loader.load(localData.applyDynamics(solidMacroAssignment)))
                                loaded = true;
                        } catch (RuntimeException e) {
                            Logger.e(e, "error while loading operation <%s> for profile <%s>", data.getClass().getSimpleName(), profile.getName());
                        }
                    }
                } else {
                    for (RemoteLocalOperationDataWrapper data : dataCollection) {
                        RemoteOperationData remoteData = data.remoteData;
                        helper.asyncTriggerOperation(pluginId, remoteData);
                    }
                }
            }
            String extraInfo;
            if (loaded) {
                extraInfo = null;
                Logger.i("Profile <%s> loaded", name);
            } else {
                extraInfo = "Profile failed to load in full";
                Logger.w("Profile <%s> not loaded (none of the operations successfully loaded", name);
            }
            if (event == null) {
                ActivityLogService.Companion.notifyProfileLoaded(this, name, extraInfo);
            } else {
                ActivityLogService.Companion.notifyScriptSatisfied(this, event, name, extraInfo);
            }
        }
    }
}
