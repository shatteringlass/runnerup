/*
 * Copyright (C) 2014 weides@gmail.com
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.runnerup.tracker.component;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Pair;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import org.runnerup.common.util.Constants;
import org.runnerup.tracker.Tracker;
import org.runnerup.tracker.WorkoutObserver;
import org.runnerup.util.Formatter;
import org.runnerup.workout.Dimension;
import org.runnerup.workout.Scope;
import org.runnerup.workout.Workout;
import org.runnerup.workout.WorkoutInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static com.google.android.gms.wearable.PutDataRequest.WEAR_URI_SCHEME;

@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class TrackerWear extends DefaultTrackerComponent
        implements Constants, TrackerComponent, WorkoutObserver, NodeApi.NodeListener,
        MessageApi.MessageListener, DataApi.DataListener {

    public static final String NAME = "WEAR";
    private Tracker tracker;
    private Context context;
    private GoogleApiClient mGoogleApiClient;
    private Formatter formatter;
    private HashSet<Node> connectedNodes = new HashSet<Node>();
    private String wearNode;

    private List<Pair<Scope, Dimension>> items = new ArrayList<Pair<Scope, Dimension>>(3);

    public TrackerWear(Tracker tracker) {
        this.tracker = tracker;
        items.add(new Pair<Scope, Dimension>(Scope.WORKOUT, Dimension.TIME));
        items.add(new Pair<Scope, Dimension>(Scope.WORKOUT, Dimension.DISTANCE));
        items.add(new Pair<Scope, Dimension>(Scope.LAP, Dimension.PACE));
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public TrackerComponent.ResultCode onInit(final Callback callback, Context context) {
        if (GooglePlayServicesUtil.isGooglePlayServicesAvailable(context) !=
                ConnectionResult.SUCCESS) {
            return ResultCode.RESULT_NOT_SUPPORTED;
        }

        try {
            context.getPackageManager().getPackageInfo("com.google.android.wearable.app",
                    PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            // android wear app is not installed => can't be paired
            return ResultCode.RESULT_NOT_SUPPORTED;
        }

        this.context = context;
        mGoogleApiClient = new GoogleApiClient.Builder(context)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(Bundle connectionHint) {
                        callback.run(TrackerWear.this, ResultCode.RESULT_OK);

                        Wearable.MessageApi.addListener(mGoogleApiClient, TrackerWear.this);
                        Wearable.NodeApi.addListener(mGoogleApiClient, TrackerWear.this);
                        Wearable.DataApi.addListener(mGoogleApiClient, TrackerWear.this);

                        /** get info about connected nodes in background */
                        Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).setResultCallback(
                                new ResultCallback<NodeApi.GetConnectedNodesResult>() {

                                    @Override
                                    public void onResult(NodeApi.GetConnectedNodesResult nodes) {
                                        for (Node node : nodes.getNodes()) {
                                            onPeerConnected(node);
                                        }
                                    }
                                });
                    }

                    @Override
                    public void onConnectionSuspended(int cause) {
                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(ConnectionResult result) {
                        callback.run(TrackerWear.this, ResultCode.RESULT_ERROR);
                    }
                })
                .addApi(Wearable.API)
                .build();
        mGoogleApiClient.connect();
        return ResultCode.RESULT_PENDING;
    }

    @Override
    public void onBind(HashMap<String, Object> bindValues) {
        formatter = (Formatter) bindValues.get(Workout.KEY_FORMATTER);
    }

    @Override
    public void onStart() {
        Bundle b = new Bundle();
        int i = 0;
        for (Pair<Scope, Dimension> item : items) {
            b.putString(Wear.RunInfo.HEADER + i, context.getString(item.second.getTextId()));
            i++;
        }
        setData(Wear.Path.HEADERS, b);
    }

    private void setData(String path, Bundle b) {
        Wearable.DataApi.putDataItem(mGoogleApiClient,
                PutDataRequest.create(path).setData(DataMap.fromBundle(b).toByteArray()))
                .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                    @Override
                    public void onResult(DataApi.DataItemResult dataItemResult) {
                        if (!dataItemResult.getStatus().isSuccess()) {
                            System.err.println("TrackerWear: ERROR: failed to putDataItem, " +
                                    "status code: " + dataItemResult.getStatus().getStatusCode());
                        }
                    }
                });
    }

    @Override
    public void workoutEvent(WorkoutInfo workoutInfo, int type) {
        if (!isConnected())
            return;

        Bundle b = new Bundle();
        {
            int i = 0;
            for (Pair<Scope, Dimension> item : items) {
                b.putString(Wear.RunInfo.DATA + i, formatter.format(Formatter.TXT_SHORT,
                        item.second, workoutInfo.get(item.first, item.second)));
                i++;
            }
        }

        Wearable.MessageApi.sendMessage(mGoogleApiClient, wearNode, Wear.Path.MSG_WORKOUT_EVENT,
                DataMap.fromBundle(b).toByteArray());
    }

    @Override
    public void onComplete(boolean discarded) {
        /* clear HEADERS */
        Wearable.DataApi.deleteDataItems(mGoogleApiClient,
                new Uri.Builder().scheme(WEAR_URI_SCHEME).path(
                        Wear.Path.HEADERS).build());

        /* clear WORKOUT PLAN */
        Wearable.DataApi.deleteDataItems(mGoogleApiClient,
                new Uri.Builder().scheme(WEAR_URI_SCHEME).path(
                        Wear.Path.WORKOUT_PLAN).build());
    }

    @Override
    public boolean isConnected() {
        if (mGoogleApiClient == null)
            return false;

        if (!mGoogleApiClient.isConnected())
            return false;

        return wearNode != null;
    }

    @Override
    public void onPeerConnected(Node node) {
        connectedNodes.add(node);
    }

    @Override
    public void onPeerDisconnected(Node node) {
        connectedNodes.remove(node);
        if (node.getId().contentEquals(wearNode))
            wearNode = null;
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        System.err.println("onMessageReceived: " + messageEvent);
    }

    @Override
    public ResultCode onEnd(Callback callback, Context context) {
        if (mGoogleApiClient != null) {
            if (mGoogleApiClient.isConnected()) {
                clearData();
                wearNode = null;

                Wearable.MessageApi.removeListener(mGoogleApiClient, this);
                Wearable.NodeApi.removeListener(mGoogleApiClient, this);
                Wearable.DataApi.removeListener(mGoogleApiClient, this);
                connectedNodes.clear();
            }
            mGoogleApiClient.disconnect();
            mGoogleApiClient = null;
        }
        return ResultCode.RESULT_OK;
    }

    private void clearData() {
        /* clear our node id */
        Wearable.DataApi.deleteDataItems(mGoogleApiClient,
                new Uri.Builder().scheme(WEAR_URI_SCHEME).path(
                        Wear.Path.PHONE_NODE_ID).build());

        /* clear HEADERS */
        Wearable.DataApi.deleteDataItems(mGoogleApiClient,
                new Uri.Builder().scheme(WEAR_URI_SCHEME).path(
                        Wear.Path.HEADERS).build());

        /* clear WORKOUT PLAN */
        Wearable.DataApi.deleteDataItems(mGoogleApiClient,
                new Uri.Builder().scheme(WEAR_URI_SCHEME).path(
                        Wear.Path.WORKOUT_PLAN).build());
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent ev : dataEvents) {
            System.err.println("onDataChanged: " + ev.getDataItem().getUri());
            String path = ev.getDataItem().getUri().getPath();
            if (Constants.Wear.Path.WEAR_NODE_ID.contentEquals(path)) {
                setWearNode(ev);
            }
        }
    }

    private void setWearNode(DataEvent ev) {
        if (ev.getType() == DataEvent.TYPE_CHANGED) {
            wearNode = new String(ev.getDataItem().getData());
        } else if (ev.getType() == DataEvent.TYPE_DELETED) {
            wearNode = null;
        }
    }
}