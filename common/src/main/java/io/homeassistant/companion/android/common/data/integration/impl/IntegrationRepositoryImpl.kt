package io.homeassistant.companion.android.common.data.integration.impl

import android.util.Log
import io.homeassistant.companion.android.common.BuildConfig
import io.homeassistant.companion.android.common.data.HomeAssistantVersion
import io.homeassistant.companion.android.common.data.LocalStorage
import io.homeassistant.companion.android.common.data.authentication.AuthenticationRepository
import io.homeassistant.companion.android.common.data.integration.ControlsAuthRequiredSetting
import io.homeassistant.companion.android.common.data.integration.DeviceRegistration
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.integration.IntegrationException
import io.homeassistant.companion.android.common.data.integration.IntegrationRepository
import io.homeassistant.companion.android.common.data.integration.SensorRegistration
import io.homeassistant.companion.android.common.data.integration.Service
import io.homeassistant.companion.android.common.data.integration.UpdateLocation
import io.homeassistant.companion.android.common.data.integration.ZoneAttributes
import io.homeassistant.companion.android.common.data.integration.impl.entities.EntityResponse
import io.homeassistant.companion.android.common.data.integration.impl.entities.FireEventRequest
import io.homeassistant.companion.android.common.data.integration.impl.entities.IntegrationRequest
import io.homeassistant.companion.android.common.data.integration.impl.entities.RateLimitRequest
import io.homeassistant.companion.android.common.data.integration.impl.entities.RateLimitResponse
import io.homeassistant.companion.android.common.data.integration.impl.entities.RegisterDeviceRequest
import io.homeassistant.companion.android.common.data.integration.impl.entities.SensorRequest
import io.homeassistant.companion.android.common.data.integration.impl.entities.ServiceCallRequest
import io.homeassistant.companion.android.common.data.integration.impl.entities.Template
import io.homeassistant.companion.android.common.data.integration.impl.entities.UpdateLocationRequest
import io.homeassistant.companion.android.common.data.url.UrlRepository
import io.homeassistant.companion.android.common.data.websocket.WebSocketRepository
import io.homeassistant.companion.android.common.data.websocket.impl.entities.GetConfigResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named

class IntegrationRepositoryImpl @Inject constructor(
    private val integrationService: IntegrationService,
    private val authenticationRepository: AuthenticationRepository,
    private val urlRepository: UrlRepository,
    private val webSocketRepository: WebSocketRepository,
    @Named("integration") private val localStorage: LocalStorage,
    @Named("manufacturer") private val manufacturer: String,
    @Named("model") private val model: String,
    @Named("osVersion") private val osVersion: String,
    @Named("deviceId") private val deviceId: String
) : IntegrationRepository {

    companion object {
        private const val APP_ID = "io.homeassistant.companion.android"
        private const val APP_NAME = "Home Assistant"
        private const val OS_NAME = "Android"
        private const val PUSH_URL = BuildConfig.PUSH_URL

        private const val PREF_APP_VERSION = "app_version"
        private const val PREF_DEVICE_NAME = "device_name"
        private const val PREF_PUSH_TOKEN = "push_token"

        private const val PREF_SECRET = "secret"

        private const val PREF_CHECK_SENSOR_REGISTRATION_NEXT = "sensor_reg_last"
        private const val PREF_TILE_SHORTCUTS = "tile_shortcuts_list"
        private const val PREF_SHOW_TILE_SHORTCUTS_TEXT = "show_tile_shortcuts_text"
        private const val PREF_TILE_TEMPLATE = "tile_template"
        private const val PREF_TILE_TEMPLATE_REFRESH_INTERVAL = "tile_template_refresh_interval"
        private const val PREF_WEAR_HAPTIC_FEEDBACK = "wear_haptic_feedback"
        private const val PREF_WEAR_TOAST_CONFIRMATION = "wear_toast_confirmation"
        private const val PREF_HA_VERSION = "ha_version"
        private const val PREF_AUTOPLAY_VIDEO = "autoplay_video"
        private const val PREF_FULLSCREEN_ENABLED = "fullscreen_enabled"
        private const val PREF_KEEP_SCREEN_ON_ENABLED = "keep_screen_on_enabled"
        private const val PREF_PINCH_TO_ZOOM_ENABLED = "pinch_to_zoom_enabled"
        private const val PREF_WEBVIEW_DEBUG_ENABLED = "webview_debug_enabled"
        private const val PREF_SESSION_TIMEOUT = "session_timeout"
        private const val PREF_SESSION_EXPIRE = "session_expire"
        private const val PREF_CONTROLS_AUTH_REQUIRED = "controls_auth_required"
        private const val PREF_CONTROLS_AUTH_ENTITIES = "controls_auth_entities"
        private const val PREF_SEC_WARNING_NEXT = "sec_warning_last"
        private const val TAG = "IntegrationRepository"
        private const val RATE_LIMIT_URL = BuildConfig.RATE_LIMIT_URL
    }

    override suspend fun registerDevice(deviceRegistration: DeviceRegistration) {
        val request = createUpdateRegistrationRequest(deviceRegistration)
        request.appId = APP_ID
        request.appName = APP_NAME
        request.osName = OS_NAME
        request.supportsEncryption = false
        request.deviceId = deviceId

        val url = urlRepository.getUrl()?.toHttpUrlOrNull()
        if (url == null) {
            Log.e(TAG, "Unable to register device due to missing URL")
            return
        }
        val response =
            integrationService.registerDevice(
                url.newBuilder().addPathSegments("api/mobile_app/registrations").build(),
                authenticationRepository.buildBearerToken(),
                request
            )
        try {
            persistDeviceRegistration(deviceRegistration)
            urlRepository.saveRegistrationUrls(
                response.cloudhookUrl,
                response.remoteUiUrl,
                response.webhookId
            )
            localStorage.putString(PREF_SECRET, response.secret)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to save device registration", e)
        }
    }

    override suspend fun updateRegistration(deviceRegistration: DeviceRegistration) {
        val request =
            IntegrationRequest(
                "update_registration",
                createUpdateRegistrationRequest(deviceRegistration)
            )
        var causeException: Exception? = null
        for (it in urlRepository.getApiUrls()) {
            try {
                if (integrationService.callWebhook(it.toHttpUrlOrNull()!!, request).isSuccessful) {
                    persistDeviceRegistration(deviceRegistration)
                    return
                }
            } catch (e: Exception) {
                if (causeException == null) causeException = e
                // Ignore failure until we are out of URLS to try, but use the first exception as cause exception
            }
        }

        if (causeException != null) throw IntegrationException(causeException)
        else throw IntegrationException("Error calling integration request update_registration")
    }

    override suspend fun getRegistration(): DeviceRegistration {
        return DeviceRegistration(
            localStorage.getString(PREF_APP_VERSION),
            localStorage.getString(PREF_DEVICE_NAME),
            localStorage.getString(PREF_PUSH_TOKEN)
        )
    }

    private suspend fun persistDeviceRegistration(deviceRegistration: DeviceRegistration) {
        if (deviceRegistration.appVersion != null)
            localStorage.putString(PREF_APP_VERSION, deviceRegistration.appVersion)
        if (deviceRegistration.deviceName != null)
            localStorage.putString(PREF_DEVICE_NAME, deviceRegistration.deviceName)
        if (deviceRegistration.pushToken != null)
            localStorage.putString(PREF_PUSH_TOKEN, deviceRegistration.pushToken)
    }

    override suspend fun isRegistered(): Boolean {
        return urlRepository.getApiUrls().isNotEmpty()
    }

    override suspend fun renderTemplate(template: String, variables: Map<String, String>): String? {
        var causeException: Exception? = null
        for (it in urlRepository.getApiUrls()) {
            try {
                return integrationService.getTemplate(
                    it.toHttpUrlOrNull()!!,
                    IntegrationRequest(
                        "render_template",
                        mapOf("template" to Template(template, variables))
                    )
                )["template"]
            } catch (e: Exception) {
                if (causeException == null) causeException = e
                // Ignore failure until we are out of URLS to try, but use the first exception as cause exception
            }
        }

        if (causeException != null) throw IntegrationException(causeException)
        else throw IntegrationException("Error calling integration request render_template")
    }

    override suspend fun getTemplateUpdates(template: String): Flow<String>? {
        return webSocketRepository.getTemplateUpdates(template)
            ?.map {
                it.result
            }
    }

    override suspend fun updateLocation(updateLocation: UpdateLocation) {
        val updateLocationRequest = createUpdateLocation(updateLocation)

        var causeException: Exception? = null
        for (it in urlRepository.getApiUrls()) {
            var wasSuccess = false
            try {
                wasSuccess =
                    integrationService.callWebhook(
                        it.toHttpUrlOrNull()!!,
                        updateLocationRequest
                    ).isSuccessful
            } catch (e: Exception) {
                if (causeException == null) causeException = e
                // Ignore failure until we are out of URLS to try, but use the first exception as cause exception
            }
            // if we had a successful call we can return
            if (wasSuccess)
                return
        }

        if (causeException != null) throw IntegrationException(causeException)
        else throw IntegrationException("Error calling integration request update_location")
    }

    override suspend fun callService(
        domain: String,
        service: String,
        serviceData: HashMap<String, Any>
    ) {
        var wasSuccess = false

        val serviceCallRequest =
            ServiceCallRequest(
                domain,
                service,
                serviceData
            )

        var causeException: Exception? = null
        for (it in urlRepository.getApiUrls()) {
            try {
                wasSuccess =
                    integrationService.callWebhook(
                        it.toHttpUrlOrNull()!!,
                        IntegrationRequest(
                            "call_service",
                            serviceCallRequest
                        )
                    ).isSuccessful
            } catch (e: Exception) {
                if (causeException == null) causeException = e
                // Ignore failure until we are out of URLS to try, but use the first exception as cause exception
            }
            // if we had a successful call we can return
            if (wasSuccess)
                return
        }

        if (causeException != null) throw IntegrationException(causeException)
        else throw IntegrationException("Error calling integration request call_service")
    }

    override suspend fun scanTag(data: HashMap<String, Any>) {
        var wasSuccess = false

        var causeException: Exception? = null
        for (it in urlRepository.getApiUrls()) {
            try {
                wasSuccess =
                    integrationService.callWebhook(
                        it.toHttpUrlOrNull()!!,
                        IntegrationRequest(
                            "scan_tag",
                            data
                        )
                    ).isSuccessful
            } catch (e: Exception) {
                if (causeException == null) causeException = e
                // Ignore failure until we are out of URLS to try, but use the first exception as cause exception
            }
            // if we had a successful call we can return
            if (wasSuccess)
                return
        }

        if (causeException != null) throw IntegrationException(causeException)
        else throw IntegrationException("Error calling integration request scan_tag")
    }

    override suspend fun fireEvent(eventType: String, eventData: Map<String, Any>) {
        var wasSuccess = false

        val fireEventRequest = FireEventRequest(
            eventType,
            eventData.plus(Pair("device_id", deviceId))
        )

        var causeException: Exception? = null
        for (it in urlRepository.getApiUrls()) {
            try {
                wasSuccess =
                    integrationService.callWebhook(
                        it.toHttpUrlOrNull()!!,
                        IntegrationRequest(
                            "fire_event",
                            fireEventRequest
                        )
                    ).isSuccessful
            } catch (e: Exception) {
                if (causeException == null) causeException = e
                // Ignore failure until we are out of URLS to try, but use the first exception as cause exception
            }
            // if we had a successful call we can return
            if (wasSuccess)
                return
        }

        if (causeException != null) throw IntegrationException(causeException)
        else throw IntegrationException("Error calling integration request fire_event")
    }

    override suspend fun getZones(): Array<Entity<ZoneAttributes>> {
        val getZonesRequest =
            IntegrationRequest(
                "get_zones",
                null
            )
        var causeException: Exception? = null
        var zones: Array<EntityResponse<ZoneAttributes>>? = null
        for (it in urlRepository.getApiUrls()) {
            try {
                zones = integrationService.getZones(it.toHttpUrlOrNull()!!, getZonesRequest)
            } catch (e: Exception) {
                if (causeException == null) causeException = e
                // Ignore failure until we are out of URLS to try, but use the first exception as cause exception
            }

            if (zones != null) {
                return createZonesResponse(zones)
            }
        }

        if (causeException != null) throw IntegrationException(causeException)
        else throw IntegrationException("Error calling integration request get_zones")
    }

    override suspend fun setFullScreenEnabled(enabled: Boolean) {
        localStorage.putBoolean(PREF_FULLSCREEN_ENABLED, enabled)
    }

    override suspend fun isFullScreenEnabled(): Boolean {
        return localStorage.getBoolean(PREF_FULLSCREEN_ENABLED)
    }

    override suspend fun setKeepScreenOnEnabled(enabled: Boolean) {
        localStorage.putBoolean(PREF_KEEP_SCREEN_ON_ENABLED, enabled)
    }

    override suspend fun isKeepScreenOnEnabled(): Boolean {
        return localStorage.getBoolean(PREF_KEEP_SCREEN_ON_ENABLED)
    }

    override suspend fun setPinchToZoomEnabled(enabled: Boolean) {
        localStorage.putBoolean(PREF_PINCH_TO_ZOOM_ENABLED, enabled)
    }

    override suspend fun isPinchToZoomEnabled(): Boolean {
        return localStorage.getBoolean(PREF_PINCH_TO_ZOOM_ENABLED)
    }

    override suspend fun setWebViewDebugEnabled(enabled: Boolean) {
        localStorage.putBoolean(PREF_WEBVIEW_DEBUG_ENABLED, enabled)
    }

    override suspend fun isWebViewDebugEnabled(): Boolean {
        return localStorage.getBoolean(PREF_WEBVIEW_DEBUG_ENABLED)
    }

    override suspend fun isAutoPlayVideoEnabled(): Boolean {
        return localStorage.getBoolean(PREF_AUTOPLAY_VIDEO)
    }

    override suspend fun setAutoPlayVideo(enabled: Boolean) {
        localStorage.putBoolean(PREF_AUTOPLAY_VIDEO, enabled)
    }

    override suspend fun sessionTimeOut(value: Int) {
        localStorage.putInt(PREF_SESSION_TIMEOUT, value)
    }

    override suspend fun getSessionTimeOut(): Int {
        return localStorage.getInt(PREF_SESSION_TIMEOUT) ?: 0
    }

    override suspend fun setSessionExpireMillis(value: Long) {
        localStorage.putLong(PREF_SESSION_EXPIRE, value)
    }

    override suspend fun getSessionExpireMillis(): Long {
        return localStorage.getLong(PREF_SESSION_EXPIRE) ?: 0
    }

    override suspend fun setControlsAuthRequired(setting: ControlsAuthRequiredSetting) {
        localStorage.putString(PREF_CONTROLS_AUTH_REQUIRED, setting.name)
    }

    override suspend fun getControlsAuthRequired(): ControlsAuthRequiredSetting {
        val current = localStorage.getString(PREF_CONTROLS_AUTH_REQUIRED)
        return ControlsAuthRequiredSetting.values().firstOrNull {
            it.name == current
        } ?: ControlsAuthRequiredSetting.NONE
    }

    override suspend fun setControlsAuthEntities(entities: List<String>) {
        localStorage.putStringSet(PREF_CONTROLS_AUTH_ENTITIES, entities.toSet())
    }

    override suspend fun getControlsAuthEntities(): List<String> {
        return localStorage.getStringSet(PREF_CONTROLS_AUTH_ENTITIES)?.toList() ?: emptyList()
    }

    override suspend fun getTileShortcuts(): List<String> {
        val jsonArray = JSONArray(localStorage.getString(PREF_TILE_SHORTCUTS) ?: "[]")
        return List(jsonArray.length()) {
            jsonArray.getString(it)
        }
    }

    override suspend fun setTileShortcuts(entities: List<String>) {
        localStorage.putString(PREF_TILE_SHORTCUTS, JSONArray(entities).toString())
    }

    override suspend fun getTemplateTileContent(): String {
        return localStorage.getString(PREF_TILE_TEMPLATE) ?: ""
    }

    override suspend fun setTemplateTileContent(content: String) {
        localStorage.putString(PREF_TILE_TEMPLATE, content)
    }

    override suspend fun getTemplateTileRefreshInterval(): Int {
        return localStorage.getInt(PREF_TILE_TEMPLATE_REFRESH_INTERVAL) ?: 0
    }

    override suspend fun setTemplateTileRefreshInterval(interval: Int) {
        localStorage.putInt(PREF_TILE_TEMPLATE_REFRESH_INTERVAL, interval)
    }

    override suspend fun setWearHapticFeedback(enabled: Boolean) {
        localStorage.putBoolean(PREF_WEAR_HAPTIC_FEEDBACK, enabled)
    }

    override suspend fun getWearHapticFeedback(): Boolean {
        return localStorage.getBoolean(PREF_WEAR_HAPTIC_FEEDBACK)
    }

    override suspend fun setWearToastConfirmation(enabled: Boolean) {
        localStorage.putBoolean(PREF_WEAR_TOAST_CONFIRMATION, enabled)
    }

    override suspend fun getWearToastConfirmation(): Boolean {
        return localStorage.getBoolean(PREF_WEAR_TOAST_CONFIRMATION)
    }

    override suspend fun setShowShortcutTextEnabled(enabled: Boolean) {
        localStorage.putBoolean(PREF_SHOW_TILE_SHORTCUTS_TEXT, enabled)
    }

    override suspend fun getShowShortcutText(): Boolean {
        return localStorage.getBoolean(PREF_SHOW_TILE_SHORTCUTS_TEXT)
    }

    override suspend fun getNotificationRateLimits(): RateLimitResponse {
        val pushToken = localStorage.getString(PREF_PUSH_TOKEN) ?: ""
        val requestBody = RateLimitRequest(pushToken)
        var checkRateLimits: RateLimitResponse? = null

        var causeException: Exception? = null

        try {
            checkRateLimits =
                integrationService.getRateLimit(RATE_LIMIT_URL, requestBody).rateLimits
        } catch (e: Exception) {
            causeException = e
            Log.e(TAG, "Unable to get notification rate limits", e)
        }
        if (checkRateLimits != null)
            return checkRateLimits

        if (causeException != null) throw IntegrationException(causeException)
        else throw IntegrationException("Error calling checkRateLimits")
    }

    override suspend fun getHomeAssistantVersion(): String {
        val current = System.currentTimeMillis()
        val next = localStorage.getLong(PREF_CHECK_SENSOR_REGISTRATION_NEXT) ?: 0
        if (current <= next)
            return localStorage.getString(PREF_HA_VERSION)
                ?: "" // Skip checking HA version as it has not been 4 hours yet

        return try {
            webSocketRepository.getConfig()?.let { response ->
                localStorage.putString(PREF_HA_VERSION, response.version)
                localStorage.putLong(
                    PREF_CHECK_SENSOR_REGISTRATION_NEXT,
                    current + TimeUnit.HOURS.toMillis(4)
                )
                response.version
            } ?: run {
                Log.e(TAG, "Issue getting config from core.")
                localStorage.getString(PREF_HA_VERSION) ?: ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Issue getting new version from core.", e)
            localStorage.getString(PREF_HA_VERSION) ?: ""
        }
    }

    override suspend fun isHomeAssistantVersionAtLeast(
        year: Int,
        month: Int,
        release: Int
    ): Boolean {
        if (!isRegistered()) return false

        val version = HomeAssistantVersion.fromString(getHomeAssistantVersion())
        return version?.isAtLeast(year, month, release) ?: false
    }

    override suspend fun getConfig(): GetConfigResponse {
        val getConfigRequest =
            IntegrationRequest(
                "get_config",
                null
            )
        var response: GetConfigResponse? = null
        var causeException: Exception? = null

        for (it in urlRepository.getApiUrls()) {
            try {
                response = integrationService.getConfig(it.toHttpUrlOrNull()!!, getConfigRequest)
            } catch (e: Exception) {
                if (causeException == null) causeException = e
                // Ignore failure until we are out of URLS to try, but use the first exception as cause exception
            }

            if (response != null) {
                // If we have a valid response, also update the cached version
                localStorage.putString(PREF_HA_VERSION, response.version)
                localStorage.putLong(
                    PREF_CHECK_SENSOR_REGISTRATION_NEXT,
                    System.currentTimeMillis() + TimeUnit.HOURS.toMillis(4)
                )
                return response
            }
        }

        if (causeException != null) throw IntegrationException(causeException)
        else throw IntegrationException("Error calling integration request get_config")
    }

    override suspend fun getServices(): List<Service>? {
        val response = webSocketRepository.getServices()

        return response?.flatMap {
            it.services.map { service ->
                Service(it.domain, service.key, service.value)
            }
        }?.toList()
    }

    override suspend fun getEntities(): List<Entity<Any>>? {
        val response = webSocketRepository.getStates()

        return response?.map {
            Entity(
                it.entityId,
                it.state,
                it.attributes,
                it.lastChanged,
                it.lastUpdated,
                it.context
            )
        }
            ?.sortedBy { it.entityId }
            ?.toList()
    }

    override suspend fun getEntity(entityId: String): Entity<Map<String, Any>>? {
        val url = urlRepository.getUrl()?.toHttpUrlOrNull()
        if (url == null) {
            Log.e(TAG, "Unable to register device due to missing URL")
            return null
        }

        val response = integrationService.getState(
            url.newBuilder().addPathSegments("api/states/$entityId").build(),
            authenticationRepository.buildBearerToken()
        )
        return Entity(
            response.entityId,
            response.state,
            response.attributes,
            response.lastChanged,
            response.lastUpdated,
            response.context
        )
    }

    override suspend fun getEntityUpdates(): Flow<Entity<*>>? {
        return webSocketRepository.getStateChanges()
            ?.filter { it.newState != null }
            ?.map {
                Entity(
                    it.newState!!.entityId,
                    it.newState.state,
                    it.newState.attributes,
                    it.newState.lastChanged,
                    it.newState.lastUpdated,
                    it.newState.context
                )
            }
    }

    override suspend fun getEntityUpdates(entityIds: List<String>): Flow<Entity<*>>? {
        return webSocketRepository.getStateChanges(entityIds)
            ?.filter { it.toState != null }
            ?.map {
                Entity(
                    it.toState!!.entityId,
                    it.toState.state,
                    it.toState.attributes,
                    it.toState.lastChanged,
                    it.toState.lastUpdated,
                    it.toState.context
                )
            }
    }

    override suspend fun registerSensor(sensorRegistration: SensorRegistration<Any>) {
        val canRegisterCategoryStateClass = isHomeAssistantVersionAtLeast(2021, 11, 0)
        val canRegisterEntityDisabledState = isHomeAssistantVersionAtLeast(2022, 6, 0)
        val integrationRequest = IntegrationRequest(
            "register_sensor",
            SensorRequest(
                sensorRegistration.uniqueId,
                if (canRegisterEntityDisabledState && sensorRegistration.disabled) null else sensorRegistration.state,
                sensorRegistration.type,
                sensorRegistration.icon,
                sensorRegistration.attributes,
                sensorRegistration.name,
                sensorRegistration.deviceClass,
                sensorRegistration.unitOfMeasurement,
                if (canRegisterCategoryStateClass) sensorRegistration.stateClass else null,
                if (canRegisterCategoryStateClass) sensorRegistration.entityCategory else null,
                if (canRegisterEntityDisabledState) sensorRegistration.disabled else null
            )
        )

        var causeException: Exception? = null
        for (it in urlRepository.getApiUrls()) {
            try {
                integrationService.callWebhook(it.toHttpUrlOrNull()!!, integrationRequest).let {
                    // If we created sensor or it already exists
                    if (it.isSuccessful || it.code() == 409) {
                        return
                    }
                }
            } catch (e: Exception) {
                if (causeException == null) causeException = e
                // Ignore failure until we are out of URLS to try, but use the first exception as cause exception
            }
        }
        if (causeException != null) throw IntegrationException(causeException)
        else throw IntegrationException("Error calling integration request register_sensor")
    }

    override suspend fun updateSensors(sensors: Array<SensorRegistration<Any>>): Boolean {
        val integrationRequest = IntegrationRequest(
            "update_sensor_states",
            sensors.map {
                SensorRequest(
                    it.uniqueId,
                    it.state,
                    it.type,
                    it.icon,
                    it.attributes
                )
            }
        )

        var causeException: Exception? = null
        for (it in urlRepository.getApiUrls()) {
            try {
                integrationService.updateSensors(it.toHttpUrlOrNull()!!, integrationRequest).let {
                    it.forEach { (_, response) ->
                        if (response["success"] == false) {
                            return false
                        }
                    }
                    return true
                }
            } catch (e: Exception) {
                if (causeException == null) causeException = e
                // Ignore failure until we are out of URLS to try, but use the first exception as cause exception
            }
        }

        if (causeException != null) throw IntegrationException(causeException)
        else throw IntegrationException("Error calling integration update_sensor_states")
    }

    override suspend fun shouldNotifySecurityWarning(): Boolean {
        val current = System.currentTimeMillis()
        val next = localStorage.getLong(PREF_SEC_WARNING_NEXT) ?: 0
        return if (current > next) {
            localStorage.putLong(PREF_SEC_WARNING_NEXT, current + (86400000)) // 24 hours
            true
        } else {
            false
        }
    }

    private suspend fun createUpdateRegistrationRequest(deviceRegistration: DeviceRegistration): RegisterDeviceRequest {
        val oldDeviceRegistration = getRegistration()
        val pushToken = deviceRegistration.pushToken ?: oldDeviceRegistration.pushToken

        val appData = mutableMapOf<String, Any>("push_websocket_channel" to true)
        if (!pushToken.isNullOrBlank()) {
            appData["push_url"] = PUSH_URL
            appData["push_token"] = pushToken
        }

        return RegisterDeviceRequest(
            null,
            null,
            deviceRegistration.appVersion ?: oldDeviceRegistration.appVersion,
            deviceRegistration.deviceName ?: oldDeviceRegistration.deviceName,
            manufacturer,
            model,
            null,
            osVersion,
            null,
            appData,
            null
        )
    }

    private fun createUpdateLocation(updateLocation: UpdateLocation): IntegrationRequest {
        return IntegrationRequest(
            "update_location",
            UpdateLocationRequest(
                updateLocation.gps,
                updateLocation.gpsAccuracy,
                updateLocation.speed,
                updateLocation.altitude,
                updateLocation.course,
                updateLocation.verticalAccuracy
            )
        )
    }

    private fun createZonesResponse(zones: Array<EntityResponse<ZoneAttributes>>): Array<Entity<ZoneAttributes>> {
        val retVal = ArrayList<Entity<ZoneAttributes>>()
        zones.forEach {
            retVal.add(
                Entity(
                    it.entityId,
                    it.state,
                    it.attributes,
                    it.lastChanged,
                    it.lastUpdated,
                    it.context
                )
            )
        }

        return retVal.toTypedArray()
    }
}
