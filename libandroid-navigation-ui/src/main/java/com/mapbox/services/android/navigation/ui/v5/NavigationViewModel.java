package com.mapbox.services.android.navigation.ui.v5;

import android.app.Application;
import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import com.mapbox.geojson.Point;
import com.mapbox.mapboxsdk.location.engine.LocationEngine;
import com.mapbox.services.android.navigation.ui.v5.camera.DynamicCamera;
import com.mapbox.services.android.navigation.ui.v5.instruction.BannerInstructionModel;
import com.mapbox.services.android.navigation.ui.v5.instruction.InstructionModel;
import com.mapbox.services.android.navigation.ui.v5.route.MapboxRouteFetcher;
import com.mapbox.services.android.navigation.ui.v5.summary.SummaryModel;
import com.mapbox.services.android.navigation.ui.v5.voice.NavigationSpeechPlayer;
import com.mapbox.services.android.navigation.ui.v5.voice.SpeechAnnouncement;
import com.mapbox.services.android.navigation.ui.v5.voice.SpeechPlayer;
import com.mapbox.services.android.navigation.ui.v5.voice.SpeechPlayerProvider;
import com.mapbox.services.android.navigation.v5.milestone.BannerInstructionMilestone;
import com.mapbox.services.android.navigation.v5.milestone.Milestone;
import com.mapbox.services.android.navigation.v5.milestone.MilestoneEventListener;
import com.mapbox.services.android.navigation.v5.milestone.VoiceInstructionMilestone;
import com.mapbox.services.android.navigation.v5.models.BannerInstructions;
import com.mapbox.services.android.navigation.v5.models.DirectionsCriteria;
import com.mapbox.services.android.navigation.v5.models.DirectionsRoute;
import com.mapbox.services.android.navigation.v5.models.LegStep;
import com.mapbox.services.android.navigation.v5.models.RouteLeg;
import com.mapbox.services.android.navigation.v5.models.RouteOptions;
import com.mapbox.services.android.navigation.v5.models.StepIntersection;
import com.mapbox.services.android.navigation.v5.models.StepManeuver;
import com.mapbox.services.android.navigation.v5.navigation.MapboxNavigation;
import com.mapbox.services.android.navigation.v5.navigation.MapboxNavigationOptions;
import com.mapbox.services.android.navigation.v5.navigation.NavigationEventListener;
import com.mapbox.services.android.navigation.v5.navigation.NavigationTimeFormat;
import com.mapbox.services.android.navigation.v5.navigation.camera.Camera;
import com.mapbox.services.android.navigation.v5.offroute.OffRouteListener;
import com.mapbox.services.android.navigation.v5.route.FasterRouteListener;
import com.mapbox.services.android.navigation.v5.routeprogress.RouteProgress;
import com.mapbox.services.android.navigation.v5.utils.DistanceFormatter;
import com.mapbox.services.android.navigation.v5.utils.LocaleUtils;
import com.mapbox.services.android.navigation.v5.utils.RouteUtils;
import com.mapbox.services.android.navigation.v5.utils.ValidationUtils;

import org.jetbrains.annotations.TestOnly;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.osmdroid.bonuspack.routing.Road;
import org.osmdroid.bonuspack.routing.RoadLeg;
import org.osmdroid.bonuspack.routing.RoadManager;
import org.osmdroid.bonuspack.routing.RoadNode;
import org.osmdroid.bonuspack.utils.PolylineEncoder;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.bonuspack.routing.OSRMRoadManager;

public class NavigationViewModel extends AndroidViewModel {

    private static final String EMPTY_STRING = "";
    private static final String OKHTTP_INSTRUCTION_CACHE = "okhttp-instruction-cache";
    private static final long TEN_MEGABYTE_CACHE_SIZE = 10 * 1024 * 1024;

    public final MutableLiveData<InstructionModel> instructionModel = new MutableLiveData<>();
    public final MutableLiveData<BannerInstructionModel> bannerInstructionModel = new MutableLiveData<>();
    public final MutableLiveData<SummaryModel> summaryModel = new MutableLiveData<>();
    public final MutableLiveData<Boolean> isOffRoute = new MutableLiveData<>();
    private final MutableLiveData<Location> navigationLocation = new MutableLiveData<>();
    private final MutableLiveData<DirectionsRoute> route = new MutableLiveData<>();
    private final MutableLiveData<Boolean> shouldRecordScreenshot = new MutableLiveData<>();
    private final MutableLiveData<Point> destination = new MutableLiveData<>();

    private MapboxNavigation navigation;
    private NavigationViewRouter router;
    private LocationEngineConductor locationEngineConductor;
    private NavigationViewEventDispatcher navigationViewEventDispatcher;
    private SpeechPlayer speechPlayer;
    private int voiceInstructionsToAnnounce = 0;
    private RouteProgress routeProgress;

    Milestone milestone;
    private String language;
    private RouteUtils routeUtils;
    private LocaleUtils localeUtils;
    private DistanceFormatter distanceFormatter;
    @NavigationTimeFormat.Type
    private int timeFormatType;
    private boolean isRunning;
    private boolean isChangingConfigurations;
    private MapConnectivityController connectivityController;

    private Context context;
    private com.mapbox.api.directions.v5.models.DirectionsRoute directionsRoute;

    public NavigationViewModel(Application application) {
        super(application);
        context = application.getBaseContext();
        initializeLocationEngine();
        initializeRouter();
        this.routeUtils = new RouteUtils();
        this.localeUtils = new LocaleUtils();
        this.connectivityController = new MapConnectivityController();
    }

    @TestOnly
        // Package private (no modifier) for testing purposes
    NavigationViewModel(Application application, MapboxNavigation navigation,
                        MapConnectivityController connectivityController,
                        NavigationViewRouter router) {
        super(application);
        this.navigation = navigation;
        this.router = router;
        this.connectivityController = connectivityController;
    }

    @TestOnly
        // Package private (no modifier) for testing purposes
    NavigationViewModel(Application application, MapboxNavigation navigation,
                        LocationEngineConductor conductor, NavigationViewEventDispatcher dispatcher, SpeechPlayer speechPlayer) {
        super(application);
        this.navigation = navigation;
        this.locationEngineConductor = conductor;
        this.navigationViewEventDispatcher = dispatcher;
        this.speechPlayer = speechPlayer;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        destroyRouter();
    }

    public void onDestroy(boolean isChangingConfigurations) {
        this.isChangingConfigurations = isChangingConfigurations;
        if (!isChangingConfigurations) {
            endNavigation();
            deactivateInstructionPlayer();
            isRunning = false;
        }
        clearDynamicCameraMap();
        navigationViewEventDispatcher = null;
    }

    public void setMuted(boolean isMuted) {
        speechPlayer.setMuted(isMuted);
    }


    /**
     * Returns the current instance of {@link MapboxNavigation}.
     * <p>
     * Will be null if navigation has not been initialized.
     */
    @Nullable
    public MapboxNavigation retrieveNavigation() {
        return navigation;
    }

    void initializeEventDispatcher(NavigationViewEventDispatcher navigationViewEventDispatcher) {
        this.navigationViewEventDispatcher = navigationViewEventDispatcher;
    }

    /**
     * This method will pass {@link MapboxNavigationOptions} from the {@link NavigationViewOptions}
     * to this view model to be used to initialize {@link MapboxNavigation}.
     *
     * @param options to init MapboxNavigation
     */
    void initialize(NavigationViewOptions options) {
        MapboxNavigationOptions navigationOptions = options.navigationOptions();
        navigationOptions = navigationOptions.toBuilder().isFromNavigationUi(true).build();
        initializeLanguage(options);
        initializeTimeFormat(navigationOptions);
        initializeDistanceFormatter(options);
        if (!isRunning()) {
            LocationEngine locationEngine = initializeLocationEngineFrom(options);
            initializeNavigation(getApplication(), navigationOptions, locationEngine);
            addMilestones(options);
            initializeNavigationSpeechPlayer(options);
        }
        router.extractRouteOptions(options);
    }

    boolean isRunning() {
        return isRunning;
    }

    boolean isMuted() {
        if (speechPlayer == null) {
            return false;
        }
        return speechPlayer.isMuted();
    }

    void stopNavigation() {
        navigation.removeProgressChangeListener(null);
        navigation.removeMilestoneEventListener(null);
        navigation.stopNavigation();
    }

    boolean isOffRoute() {
        try {
            return isOffRoute.getValue();
        } catch (NullPointerException exception) {
            return false;
        }
    }

    void updateRoute(DirectionsRoute route) {
        this.route.setValue(route);
        if (!isChangingConfigurations) {
            startNavigation(route);
            updateReplayEngine(route);
            sendEventOnRerouteAlong(route);
            isOffRoute.setValue(false);
        }
        resetConfigurationFlag();
    }

    void updateRouteProgress(RouteProgress routeProgress) {
        this.routeProgress = routeProgress;
        sendEventArrival(routeProgress, milestone);
        instructionModel.setValue(new InstructionModel(distanceFormatter, routeProgress));
        summaryModel.setValue(new SummaryModel(getApplication(), distanceFormatter, routeProgress, timeFormatType));
    }

    void updateLocation(Location location) {
        router.updateLocation(location);
        navigationLocation.setValue(location);
    }

    void sendEventFailedReroute(String errorMessage) {
        if (navigationViewEventDispatcher != null) {
            navigationViewEventDispatcher.onFailedReroute(errorMessage);
        }
    }

    MutableLiveData<Location> retrieveNavigationLocation() {
        return navigationLocation;
    }

    MutableLiveData<DirectionsRoute> retrieveRoute() {
        return route;
    }

    MutableLiveData<Point> retrieveDestination() {
        return destination;
    }

    MutableLiveData<Boolean> retrieveShouldRecordScreenshot() {
        return shouldRecordScreenshot;
    }

    private void initializeRouter() {
        MapboxRouteFetcher onlineRouter = new MapboxRouteFetcher(getApplication());
        Context applicationContext = getApplication().getApplicationContext();
        ConnectivityStatusProvider connectivityStatus = new ConnectivityStatusProvider(applicationContext);
        router = new NavigationViewRouter(onlineRouter, connectivityStatus, routeEngineListener);
    }

    private void initializeLocationEngine() {
        locationEngineConductor = new LocationEngineConductor();
    }

    private void initializeLanguage(NavigationUiOptions options) {
        RouteOptions routeOptions = options.directionsRoute().routeOptions();
        language = localeUtils.inferDeviceLanguage(getApplication());
        if (routeOptions != null) {
            language = routeOptions.language();
        }
    }

    private String initializeUnitType(NavigationUiOptions options) {
        RouteOptions routeOptions = options.directionsRoute().routeOptions();
        String unitType = localeUtils.getUnitTypeForDeviceLocale(getApplication());
        if (routeOptions != null) {
            unitType = routeOptions.voiceUnits();
        }
        return unitType;
    }

    private void initializeTimeFormat(MapboxNavigationOptions options) {
        timeFormatType = options.timeFormatType();
    }

    private int initializeRoundingIncrement(NavigationViewOptions options) {
        MapboxNavigationOptions navigationOptions = options.navigationOptions();
        return navigationOptions.roundingIncrement();
    }

    private void initializeDistanceFormatter(NavigationViewOptions options) {
        String unitType = initializeUnitType(options);
        int roundingIncrement = initializeRoundingIncrement(options);
        distanceFormatter = new DistanceFormatter(getApplication(), language, unitType, roundingIncrement);
    }

    private void initializeNavigationSpeechPlayer(NavigationViewOptions options) {
        SpeechPlayer speechPlayer = options.speechPlayer();
        if (speechPlayer != null) {
            this.speechPlayer = speechPlayer;
            return;
        }
        boolean isVoiceLanguageSupported = options.directionsRoute().voiceLanguage() != null;
        SpeechPlayerProvider speechPlayerProvider = initializeSpeechPlayerProvider(isVoiceLanguageSupported);
        this.speechPlayer = new NavigationSpeechPlayer(speechPlayerProvider);
    }

    @NonNull
    private SpeechPlayerProvider initializeSpeechPlayerProvider(boolean voiceLanguageSupported) {
        return new SpeechPlayerProvider(getApplication(), language, voiceLanguageSupported);
    }

    private LocationEngine initializeLocationEngineFrom(NavigationViewOptions options) {
        LocationEngine locationEngine = options.locationEngine();
        boolean shouldReplayRoute = options.shouldSimulateRoute();
        locationEngineConductor.initializeLocationEngine(getApplication(), locationEngine, shouldReplayRoute);
        return locationEngineConductor.obtainLocationEngine();
    }

    private void initializeNavigation(Context context, MapboxNavigationOptions options, LocationEngine locationEngine) {
        navigation = new MapboxNavigation(context, options, locationEngine);
        addNavigationListeners();
    }

    private void addNavigationListeners() {
        navigation.addProgressChangeListener(new NavigationViewModelProgressChangeListener(this));
        navigation.addOffRouteListener(offRouteListener);
        navigation.addMilestoneEventListener(milestoneEventListener);
        navigation.addNavigationEventListener(navigationEventListener);
        navigation.addFasterRouteListener(fasterRouteListener);
    }

    private void addMilestones(NavigationViewOptions options) {
        List<Milestone> milestones = options.milestones();
        if (milestones != null && !milestones.isEmpty()) {
            navigation.addMilestones(milestones);
        }
    }

    private OffRouteListener offRouteListener = new OffRouteListener() {
        @Override
        public void userOffRoute(Location location) {
            speechPlayer.onOffRoute();
            Point newOrigin = Point.fromLngLat(location.getLongitude(), location.getLatitude());
            Log.v("nv2_log_aparu_driver", "handleOffRouteEvent 01 - newOrigin = " + newOrigin);
            handleOffRouteEvent(newOrigin);
        }
    };

    private MilestoneEventListener milestoneEventListener = (routeProgress, instruction, milestone) -> {
        NavigationViewModel.this.milestone = milestone;
        playVoiceAnnouncement(milestone);
        updateBannerInstruction(routeProgress, milestone);
        sendEventArrival(routeProgress, milestone);
    };

    private NavigationEventListener navigationEventListener = isRunning -> {
        NavigationViewModel.this.isRunning = isRunning;
        sendNavigationStatusEvent(isRunning);
    };

    private FasterRouteListener fasterRouteListener = directionsRoute -> updateRoute(directionsRoute);

    private ViewRouteListener routeEngineListener = new NavigationViewRouteEngineListener(this);

    private void startNavigation(DirectionsRoute route) {
        if (route != null) {
            navigation.startNavigation(route);
            voiceInstructionsToAnnounce = 0;
        }
    }

    private void updateReplayEngine(DirectionsRoute route) {
        if (locationEngineConductor.updateSimulatedRoute(route)) {
            LocationEngine replayEngine = locationEngineConductor.obtainLocationEngine();
            navigation.setLocationEngine(replayEngine);
        }
    }

    private void destroyRouter() {
        if (router != null) {
            router.onDestroy();
        }
    }

    private void endNavigation() {
        if (navigation != null) {
            navigation.onDestroy();
        }
    }

    private void clearDynamicCameraMap() {
        if (navigation != null) {
            Camera cameraEngine = navigation.getCameraEngine();
            boolean isDynamicCamera = cameraEngine instanceof DynamicCamera;
            if (isDynamicCamera) {
                ((DynamicCamera) cameraEngine).clearMap();
            }
        }
    }

    private void deactivateInstructionPlayer() {
        if (speechPlayer != null) {
            speechPlayer.onDestroy();
        }
    }

    private void playVoiceAnnouncement(Milestone milestone) {
        if (milestone instanceof VoiceInstructionMilestone) {
            voiceInstructionsToAnnounce++;
            SpeechAnnouncement announcement = SpeechAnnouncement.builder()
                .voiceInstructionMilestone((VoiceInstructionMilestone) milestone).build();
            announcement = retrieveAnnouncementFromSpeechEvent(announcement);
            speechPlayer.play(announcement);
        }
    }

    private void updateBannerInstruction(RouteProgress routeProgress, Milestone milestone) {
        if (milestone instanceof BannerInstructionMilestone) {
            BannerInstructions instructions = ((BannerInstructionMilestone) milestone).getBannerInstructions();
            instructions = retrieveInstructionsFromBannerEvent(instructions);
            if (instructions != null) {
                BannerInstructionModel model = new BannerInstructionModel(distanceFormatter, routeProgress, instructions);
                bannerInstructionModel.setValue(model);
            }
        }
    }

    private void sendEventArrival(RouteProgress routeProgress, Milestone milestone) {
        if (milestone == null || routeProgress == null) {
            return;
        }
        if (navigationViewEventDispatcher != null && routeUtils.isArrivalEvent(routeProgress, milestone)) {
            navigationViewEventDispatcher.onArrival();
        }
    }

    private void handleOffRouteEvent(Point newOrigin) {
        if (navigationViewEventDispatcher != null && navigationViewEventDispatcher.allowRerouteFrom(newOrigin)) {
            navigationViewEventDispatcher.onOffRoute(newOrigin);
            Log.v("nv2_log_aparu_driver", "findRouteFrom 01");
//            router.findRouteFrom(routeProgress);
            calculateRoute(newOrigin, true);
            isOffRoute.setValue(true);
        }
    }
    private DirectionsRoute calculateRoute(Point newOrigin, Boolean isUpdateListeners) {
        ArrayList<GeoPoint> wayPoints = new ArrayList<>();
        wayPoints.add(new GeoPoint(newOrigin.latitude(), newOrigin.longitude()));
//        if (!isUpdateListeners) {
        // нужно как то отсекать точки которые пройдены
//        }
        Point endPoint = routeProgress.directionsRoute().routeOptions().coordinates().get(routeProgress.directionsRoute().routeOptions().coordinates().size()-1);
        wayPoints.add(new GeoPoint(endPoint.latitude(), endPoint.longitude()));//endPoint
        Log.v("nv2_log_aparu_driver", "calculateRoute - wayPoints.size() = " + wayPoints.size());
        return calculateRoute(context, wayPoints, route.getValue().routeOptions().user(),
                new OnCalculateRouteCallback2() {
                    @Override
                    public void onCalculateRouteReady(DirectionsRoute route) {
                        Log.v("nv2_log_aparu_driver", "AparuNavigationViewModel2 - updateRoute 02");
                        updateRoute(route);
                        if (isUpdateListeners) {
                            Log.v("nv2_log_aparu_driver", "AparuNavigationViewModel2 - updateNavigation");
                            navigation.updateNavigation(route);
                            router.findOSMRouteFrom(route);
//                            currentLegSteps = route.legs().get(0).steps();
                            Log.v("nv2_log_aparu_driver", "AparuNavigationViewModel2 - onCalculateRouteReady - directionsRoutes.get(0).legs().steps().size = " + route.legs().get(0).steps().size());
                            //navigationMapRoute?.addRoutes(response.routes())
                        }
                    }
                });
    }

    public DirectionsRoute calculateRoute(Context context, ArrayList<GeoPoint> wayPoints, String userAgent, OnCalculateRouteCallback2 onCalculateRouteCallback2) {
//        ArrayList<GeoPoint> wayPoints  = new ArrayList<>();
        ArrayList<Road> roads = new ArrayList<>();
        final DirectionsRoute[] route = {null};

        Log.v("nv2_log_aparu_driver", "AparuNavigationViewModel - calculateRoute");
//        if (sPoint == null || routPoints == null) {
//            Log.v("nv_log_aparu_driver", "MyCalcRoute: User location is null, therefore, origin can't be set.");
//            return;
//        }
//        wayPoints.clear();
//        wayPoints.add(sPoint);
//        wayPoints.addAll(routPoints);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());

        executor.execute(new Runnable() {
            @Override
            public void run() {
                RoadManager roadManager = new OSRMRoadManager(context, userAgent);
                roads.clear();
                roads.addAll(Arrays.asList(roadManager.getRoads(wayPoints)));
                if (roads.get(0).mStatus == Road.STATUS_OK) {
                    route[0] = convertOsrmRoadToMapLibreRoute(roads.get(0), userAgent);
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (roads.get(0).mStatus == Road.STATUS_OK) {
                            Log.v("nv2_log_aparu_driver", "calculateRoute - onCalculateRouteReady 01");
                            onCalculateRouteCallback2.onCalculateRouteReady(route[0]);
                        } else {
                            // MANAGE EXCEPTIONS
                        }
                    }
                });
            }
        });
        return route[0];
    }
    private DirectionsRoute convertOsrmRoadToMapLibreRoute(Road road, String userAgent) {
        List<Point> routePoints = new ArrayList<>();
        ArrayList<org.osmdroid.util.GeoPoint> OsrmPoints;

        // Convert list of Points
        OsrmPoints = road.mRouteHigh;
        for (org.osmdroid.util.GeoPoint point : OsrmPoints) {
            routePoints.add(Point.fromLngLat(point.getLongitude(), point.getLatitude()));
        }
        Log.v("nv_log_aparu_driver", "convertOsrmRoadToMapLibreRoute - routePoints.size()" + routePoints.size());
        // BUILD RouteOptions
        RouteOptions routeOptions = RouteOptions.builder()
                .geometries(DirectionsCriteria.GEOMETRY_POLYLINE6)
                .profile(DirectionsCriteria.PROFILE_DRIVING)
                .accessToken("pk.0")                                    // fake AccessToken
                .user(userAgent)
                .requestUuid("c945b0b4-9764-11ed-a8fc-0242ac120002")    // fake UUID ver.1
                .baseUrl("www.fakeUrl.com")                             // fake url
                .coordinates(routePoints)
                .voiceInstructions(true)
                .bannerInstructions(true)
                .build();

        // BUILD RouteLegs
        List<com.mapbox.services.android.navigation.v5.models.RouteLeg> routeLegs = new ArrayList<>();
        for (RoadLeg leg : road.mLegs) {
            // BUILD LegSteps
            int indexEndNode;
            List<org.osmdroid.util.GeoPoint> roadPoints = new ArrayList<>(road.mRouteHigh);
            List<LegStep> legSteps = new ArrayList<>();
            for (RoadNode roadNode : road.mNodes) {
                // GEOMETRY of LegStep
                indexEndNode = 0;
                for (int i = 0; i < roadPoints.size(); i++) {
                    if (roadPoints.get(i).getLatitude() == roadNode.mLocation.getLatitude() && roadPoints.get(i).getLongitude() == roadNode.mLocation.getLongitude()) {
                        indexEndNode = i;
                        break;
                    }
                }
                ArrayList<org.osmdroid.util.GeoPoint> legPoints = new ArrayList<>();
                for (int i = 0; i <= indexEndNode; i++)
                    legPoints.add(roadPoints.get(i));
                roadPoints.subList(0, indexEndNode + 1).clear();
                // End GEOMETRY of LegStep

                double[] rawLocation = {roadNode.mLocation.getLongitude(), roadNode.mLocation.getLatitude()};

                List<StepIntersection> stepIntersections = new ArrayList<>();
                StepIntersection stepIntersection = StepIntersection.builder()      // No other OSM data for this
                        .rawLocation(rawLocation)
                        .build();
                stepIntersections.add(stepIntersection);

                LegStep legStep = LegStep.builder()
                        .distance(roadNode.mLength * 1000)
                        .duration(roadNode.mDuration)
                        .weight(1.0)
                        .mode("auto")
                        .intersections(stepIntersections)
                        .geometry(PolylineEncoder.encode(legPoints, 1))
                        .maneuver(StepManeuver.builder()                    // No all OSM data for this
                                .instruction(roadNode.mInstructions)
                                .rawLocation(rawLocation)
                                .bearingBefore(0.0)         // No OSM data for this
                                .bearingAfter(0.0)          // No OSM data for this
                                .build())
                        .build();

                legSteps.add(legStep);
            }
            Log.v("nv_log_aparu_driver", "convertOsrmRoadToMapLibreRoute - legSteps.size()" + legSteps.size());
            RouteLeg routeLeg = RouteLeg.builder()
                    .distance(leg.mLength)
                    .duration(leg.mDuration)
                    .steps(legSteps)
                    .build();

            routeLegs.add(routeLeg);
        }

        // Build DirectionsRoute
        return DirectionsRoute.builder()
                .legs(routeLegs)
                .geometry(PolylineEncoder.encode(road.mRouteHigh, 1))
                .weightName("auto")
                .weight(1.0)
                .distance(road.mLength * 1000)
                .duration(road.mDuration)
                .routeOptions(routeOptions)
                .build();
    }

    private void sendNavigationStatusEvent(boolean isRunning) {
        if (navigationViewEventDispatcher != null) {
            if (isRunning) {
                navigationViewEventDispatcher.onNavigationRunning();
            } else {
                navigationViewEventDispatcher.onNavigationFinished();
            }
        }
    }

    private void sendEventOnRerouteAlong(DirectionsRoute route) {
        if (navigationViewEventDispatcher != null && isOffRoute()) {
            navigationViewEventDispatcher.onRerouteAlong(route);
        }
    }

    private void resetConfigurationFlag() {
        if (isChangingConfigurations) {
            isChangingConfigurations = false;
        }
    }

    private SpeechAnnouncement retrieveAnnouncementFromSpeechEvent(SpeechAnnouncement announcement) {
        if (navigationViewEventDispatcher != null) {
            announcement = navigationViewEventDispatcher.onAnnouncement(announcement);
        }
        return announcement;
    }

    private BannerInstructions retrieveInstructionsFromBannerEvent(BannerInstructions instructions) {
        if (navigationViewEventDispatcher != null) {
            instructions = navigationViewEventDispatcher.onBannerDisplay(instructions);
        }
        return instructions;
    }

    public interface OnCalculateRouteCallback2 {
        void onCalculateRouteReady(DirectionsRoute route);
    }
}