package com.mapbox.services.android.navigation.testapp

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.DirectionsResponse
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.annotations.MarkerOptions
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.location.LocationComponent
import com.mapbox.mapboxsdk.location.LocationComponentActivationOptions
import com.mapbox.mapboxsdk.location.modes.CameraMode
import com.mapbox.mapboxsdk.location.modes.RenderMode
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.services.android.navigation.testapp.databinding.ActivityNavigationUiBinding
import com.mapbox.services.android.navigation.ui.v5.NavigationLauncher
import com.mapbox.services.android.navigation.ui.v5.NavigationLauncherOptions
import com.mapbox.services.android.navigation.ui.v5.route.NavigationRoute
import com.mapbox.services.android.navigation.v5.milestone.*
import com.mapbox.services.android.navigation.v5.models.DirectionsRoute
import com.mapbox.services.android.navigation.v5.models.LegStep
import com.mapbox.services.android.navigation.v5.models.RouteLeg
import com.mapbox.services.android.navigation.v5.models.RouteOptions
import com.mapbox.services.android.navigation.v5.models.StepIntersection
import com.mapbox.services.android.navigation.v5.models.StepManeuver
import com.mapbox.services.android.navigation.v5.navigation.*
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import okhttp3.internal.userAgent
import org.osmdroid.bonuspack.routing.OSRMRoadManager
import org.osmdroid.bonuspack.routing.Road
import org.osmdroid.bonuspack.routing.RoadManager
import org.osmdroid.bonuspack.utils.PolylineEncoder
import org.osmdroid.util.GeoPoint
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class NavigationUIActivity :
    AppCompatActivity(),
    OnMapReadyCallback,
    MapboxMap.OnMapClickListener {
    lateinit var mapboxMap: MapboxMap

    // Navigation related variables
    private var route: DirectionsRoute? = null
    private var navigationMapRoute: NavigationMapRoute? = null
    private var destination: Point? = null
    private var waypoint: Point? = null
    private var locationComponent: LocationComponent? = null

    private lateinit var binding: ActivityNavigationUiBinding

    private var simulateRoute = false

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Log.v("nv2_log_aparu_driver", "NavigationUIActivity - onCreate")
        binding = ActivityNavigationUiBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.mapView.apply {
            onCreate(savedInstanceState)
            getMapAsync(this@NavigationUIActivity)
        }

        binding.startRouteButton.setOnClickListener {
            Log.v("nv2_log_aparu_driver", "NavigationUIActivity - setOnClickListener")
            route?.let { route ->
                val userLocation = mapboxMap.locationComponent.lastKnownLocation ?: return@let
                Log.v("nv2_log_aparu_driver", "NavigationUIActivity - setOnClickListener 01")
                val options = NavigationLauncherOptions.builder()
                    .directionsRoute(route)
                    .shouldSimulateRoute(simulateRoute)
                    .initialMapCameraPosition(CameraPosition.Builder().target(LatLng(userLocation.latitude, userLocation.longitude)).build())
                    .lightThemeResId(R.style.TestNavigationViewLight)
                    .darkThemeResId(R.style.TestNavigationViewDark)
                    .build()
                NavigationLauncher.startNavigation(this@NavigationUIActivity, options)
            }
        }

        binding.simulateRouteSwitch.setOnCheckedChangeListener { _, checked ->
            simulateRoute = checked
        }


        binding.clearPoints.setOnClickListener {
            if (::mapboxMap.isInitialized) {
                mapboxMap.markers.forEach {
                    mapboxMap.removeMarker(it)
                }
            }
            destination = null
            waypoint = null
            it.visibility = View.GONE
            binding.startRouteLayout.visibility = View.GONE

            navigationMapRoute?.removeRoute()
        }
    }

        override fun onMapReady(mapboxMap: MapboxMap) {
        Log.v("nv2_log_aparu_driver", "onMapReady")
        this.mapboxMap = mapboxMap
        val MAPTILERKEY = "y5DBeK1Dp56ItEU51ym7"
        val styleUrl = "https://89.218.63.99:4435/App/MapStyle?key=${MAPTILERKEY}";
//        mapboxMap.setStyle(Style.Builder().fromUri(getString(R.string.map_style_light))) { style ->
//            enableLocationComponent(style)
//        }
        mapboxMap.setStyle(styleUrl, {
            enableLocationComponent(it)
        })

        navigationMapRoute = NavigationMapRoute(
            binding.mapView,
            mapboxMap
        )

        mapboxMap.addOnMapClickListener(this)
        Snackbar.make(
            findViewById(R.id.container),
            "Tap map to place waypoint",
            Snackbar.LENGTH_LONG,
        ).show()
    }

    @SuppressWarnings("MissingPermission")
    private fun enableLocationComponent(style: Style) {
        // Get an instance of the component
        locationComponent = mapboxMap.locationComponent

        locationComponent?.let {
            // Activate with a built LocationComponentActivationOptions object
            it.activateLocationComponent(
                LocationComponentActivationOptions.builder(this, style).build(),
            )

            // Enable to make component visible
            it.isLocationComponentEnabled = true

            // Set the component's camera mode
            it.cameraMode = CameraMode.TRACKING_GPS_NORTH

            // Set the component's render mode
            it.renderMode = RenderMode.NORMAL
        }
    }

    override fun onMapClick(point: LatLng): Boolean {
        var addMarker = true
        when {
            destination == null -> destination = Point.fromLngLat(point.longitude, point.latitude)
            waypoint == null -> waypoint = Point.fromLngLat(point.longitude, point.latitude)
            else -> {
                Toast.makeText(this, "Only 2 waypoints supported", Toast.LENGTH_LONG).show()
                addMarker = false
            }
        }

        if (addMarker) {
            mapboxMap.addMarker(MarkerOptions().position(point))
            binding.clearPoints.visibility = View.VISIBLE
        }
        calculateRoute()
        return true
    }

    private fun calculateRoute() {
        binding.startRouteLayout.visibility = View.GONE
        val userLocation = mapboxMap.locationComponent.lastKnownLocation
        val destination = destination
        if (userLocation == null) {
            Timber.d("calculateRoute: User location is null, therefore, origin can't be set.")
            return
        }

        if (destination == null) {
            return
        }

        val origin = Point.fromLngLat(userLocation.longitude, userLocation.latitude)
        if (TurfMeasurement.distance(origin, destination, TurfConstants.UNIT_METERS) < 50) {
            binding.startRouteLayout.visibility = View.GONE
            return
        }

        var wayPoints: ArrayList<GeoPoint> = ArrayList()
        wayPoints.add(GeoPoint(userLocation.latitude, userLocation.longitude))
        wayPoints.add(GeoPoint(destination.latitude(), destination.longitude()))

        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        val handler = Handler(Looper.getMainLooper())
        executor.execute {
            Log.v("nv2_log_aparu_driver", "calculateRoute - wayPoints.size = " + wayPoints.size)
            val roadManager: RoadManager = OSRMRoadManager(this, userAgent)
            val roads: Array<Road>? = roadManager.getRoads(wayPoints)
            Log.v("nv2_log_aparu_driver", "calculateRoute - road.size = " + (roads as Array<out Road>?)?.size)
            Log.v("nv2_log_aparu_driver", "calculateRoute - road.routeLow?.size = " + (roads as Array<out Road>?)?.get(0)?.routeLow?.size)
            Log.v("nv2_log_aparu_driver", "calculateRoute - roads?.get(0)?.mStatus = " + roads?.get(0)?.mStatus)

            if ((roads as Array<out Road>?)?.get(0)?.mStatus === Road.STATUS_OK) {
                Log.v("nv2_log_aparu_driver", "navigation.startNavigation - 01")
                val route: DirectionsRoute? = (roads as Array<out Road>?)?.get(0)
                    ?.let { convertOsrmRoadToMapLibreRoute(it) }

                handler.post {
                    if ((roads as Array<out Road>?)?.get(0)?.mStatus === Road.STATUS_OK) {
                        Log.v("nv2_log_aparu_driver", "navigation.startNavigation - 02")
                        route?.let {
                            Log.v("nv2_log_aparu_driver", "navigation.startNavigation - 03")
                            // startNavigation(it)

                            //                        val maplibreResponse = DirectionsResponse.fromJson(response.toJson());
//                        this@OrderContainerActivity.route = maplibreResponse.routes().first()
//                        navigationMapRoute?.addRoutes(maplibreResponse.routes())
//                        binding.startRouteLayout.visibility = View.VISIBLE
//                        val userLocation = mapboxMap.locationComponent.lastKnownLocation ?: return@let

//                            val maplibreResponse = com.mapbox.services.android.navigation.v5.models.DirectionsResponse.fromJson(response.toJson());
//                            this@NavigationUIActivity.route = maplibreResponse.routes().first()
                            this@NavigationUIActivity.route = it
                            navigationMapRoute?.addRoute(it)
                            binding.startRouteLayout.visibility = View.VISIBLE

//                            val options = NavigationLauncherOptions.builder()
//                                .directionsRoute(it)
//                                .shouldSimulateRoute(true)
//                                .initialMapCameraPosition(CameraPosition.Builder().
//                                target(LatLng(userLocation.latitude, userLocation.longitude)).build())
////                            .lightThemeResId(R.style.TestNavigationViewLight)
////                            .darkThemeResId(R.style.TestNavigationViewDark)
//                                .build()
//                            Log.v("nv2_log_aparu_driver", "calculateRoute - NavigationLauncher.startNavigation")
////                            Mapbox.getInstance(AparuApplication.getContext())
//                            NavigationLauncher.startNavigation(this@NavigationUIActivity, options)



                        }
                    } else {
                        // MANAGE EXCEPTIONS
                    }
                }
            }
        }
    }

    fun convertOsrmRoadToMapLibreRoute(road: Road): DirectionsRoute? {
        val routePoints: MutableList<Point> = java.util.ArrayList()
        val OsrmPoints: java.util.ArrayList<GeoPoint>

        // Convert list of Points
        OsrmPoints = road.mRouteHigh
        for (point in OsrmPoints) {
            routePoints.add(Point.fromLngLat(point.longitude, point.latitude))
        }

        // BUILD RouteOptions
        val routeOptions: RouteOptions = RouteOptions.builder().geometries(com.mapbox.services.android.navigation.v5.models.DirectionsCriteria.GEOMETRY_POLYLINE6).profile(com.mapbox.services.android.navigation.v5.models.DirectionsCriteria.PROFILE_DRIVING).accessToken("pk.0") // fake AccessToken
            .user(userAgent).requestUuid("c945b0b4-9764-11ed-a8fc-0242ac120002") // fake UUID ver.1
            .baseUrl("www.fakeUrl.com") // fake url
            .coordinates(routePoints).voiceInstructions(true).bannerInstructions(true).build()

        // BUILD RouteLegs
        val routeLegs: MutableList<RouteLeg> = java.util.ArrayList()
        for (leg in road.mLegs) {
            // BUILD LegSteps
            var indexEndNode: Int
            val roadPoints: List<GeoPoint> = java.util.ArrayList(road.mRouteHigh)
            val legSteps: MutableList<LegStep> = java.util.ArrayList()
            for (roadNode in road.mNodes) {
                // GEOMETRY of LegStep
                indexEndNode = 0
                Log.v("nv2_log_aparu_driver", "roadPoints.size = " + roadPoints.size )
                for (i in roadPoints.indices) {
                    if (roadPoints[i].latitude == roadNode.mLocation.latitude && roadPoints[i].longitude == roadNode.mLocation.longitude) {
                        indexEndNode = i
                        break
                    }
                }
                val legPoints: java.util.ArrayList<GeoPoint> = java.util.ArrayList()
                var i = 0
                while (i <= indexEndNode) {
                    legPoints.add(roadPoints[i])
                    i++
                }
//                roadPoints.subList(0, indexEndNode + 1).clear()
                // End GEOMETRY of LegStep
                val rawLocation = doubleArrayOf(roadNode.mLocation.longitude, roadNode.mLocation.latitude)
                val stepIntersections: MutableList<StepIntersection> = java.util.ArrayList()
                val stepIntersection = StepIntersection.builder() // No other OSM data for this
                    .rawLocation(rawLocation).build()
                stepIntersections.add(stepIntersection)
                roadNode.mInstructions?.let {
                    Log.v("nv2_log_aparu_driver", "roadNode.mInstructions.length = " + it.length)
                }
                val legStep = LegStep.builder().distance(roadNode.mLength * 1000).duration(roadNode.mDuration).weight(1.0).mode("auto").intersections(stepIntersections).geometry(
                    PolylineEncoder.encode(legPoints, 1)).maneuver(
                    StepManeuver.builder() // No all OSM data for this
                    .instruction(roadNode.mInstructions).rawLocation(rawLocation).bearingBefore(0.0) // No OSM data for this
                    .bearingAfter(0.0) // No OSM data for this
                    .build()).build()
                legSteps.add(legStep)
            }
            val routeLeg = RouteLeg.builder().distance(leg.mLength).duration(leg.mDuration).steps(legSteps).build()
            routeLegs.add(routeLeg)
        }

        // Build DirectionsRoute
        return DirectionsRoute.builder().legs(routeLegs).geometry(
            PolylineEncoder.encode(road.mRouteHigh, 1)).weightName("auto").weight(1.0).distance(road.mLength * 1000).duration(road.mDuration).routeOptions(routeOptions).build()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onStop() {
        super.onStop()
        binding.mapView.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::mapboxMap.isInitialized) {
            mapboxMap.removeOnMapClickListener(this)
        }
        binding.mapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }
}
