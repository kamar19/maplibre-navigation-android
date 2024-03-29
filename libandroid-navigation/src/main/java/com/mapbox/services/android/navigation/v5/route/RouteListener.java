package com.mapbox.services.android.navigation.v5.route;

import androidx.annotation.Nullable;

import com.mapbox.services.android.navigation.v5.models.DirectionsResponse;
import com.mapbox.services.android.navigation.v5.models.DirectionsRoute;
import com.mapbox.services.android.navigation.v5.routeprogress.RouteProgress;

/**
 * Will fire when either a successful / failed response is received.
 */
public interface RouteListener {

  void onResponseReceived(DirectionsResponse response, @Nullable RouteProgress routeProgress);

  void onUpdateOSMRoute(DirectionsRoute route, @Nullable RouteProgress routeProgress);

  void onErrorReceived(Throwable throwable);
}
