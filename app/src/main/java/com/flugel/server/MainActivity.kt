package com.flugel.server

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SecureStore(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0A0B0D),
                    surface = Color(0xFF15161A),
                    primary = Color(0xFFF5F5F7),
                    onPrimary = Color(0xFF101114),
                    onSurface = Color(0xFFF5F5F7)
                )
            ) {
                Surface(Modifier.fillMaxSize()) { Dashboard(store) }
            }
        }
    }
}

private data class PendingAction(val name: String, val label: String, val path: String)

@Composable
private fun Dashboard(store: SecureStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var base by remember { mutableStateOf(store.serverUrl()) }
    var tokenPresent by remember { mutableStateOf(store.token() != null) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    val connectionError = stringResource(R.string.connection_error)
    val actionOk = stringResource(R.string.action_ok)
    val titles = listOf(R.string.summary, R.string.containers, R.string.vms, R.string.desktops,
        R.string.services, R.string.notifications, R.string.settings)
    val paths = listOf("/v1/status", "/v1/containers", "/v1/vms", "/v1/desktops",
        "/v1/links", "/v1/notifications")

    LaunchedEffect(tab, refresh, base, tokenPresent) {
        if (tab >= paths.size || base.isBlank() || !tokenPresent) {
            data = null
            return@LaunchedEffect
        }
        loading = true
        message = null
        data = try {
            val token = store.token() ?: error("missing token")
            withContext(Dispatchers.IO) { ApiClient(base, token).get(paths[tab]) }
        } catch (_: Exception) {
            message = connectionError
            null
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = { refresh++ }, enabled = tab < paths.size) { Text(stringResource(R.string.refresh)) }
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            titles.forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(title)) })
            }
        }
        if (tab == 6) {
            SettingsPage(store, base) { newBase, hasToken ->
                base = newBase
                tokenPresent = hasToken
                message = context.getString(R.string.saved)
            }
        } else if (base.isBlank() || !tokenPresent) {
            Text(stringResource(R.string.configure_first), Modifier.padding(20.dp))
        } else {
            if (loading) CircularProgressIndicator(Modifier.padding(20.dp))
            if (message != null) Text(message ?: "", Modifier.padding(20.dp))
            val value = data
            if (value != null) {
                val action: (String, String, String) -> Unit = { name, label, path ->
                    pending = PendingAction(name, label, path)
                }
                when (tab) {
                    0 -> SummaryPage(value)
                    1 -> ContainersPage(value, action)
                    2 -> VmsPage(value, action)
                    3 -> DesktopsPage(value, action)
                    4 -> LinksPage(value) { url ->
                        if (url.startsWith("https://")) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    5 -> NotificationsPage(value)
                }
            }
        }
    }

    pending?.let { item ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.confirm)) },
            text = { Text(stringResource(R.string.confirm_action, item.name, item.label)) },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                Button(onClick = {
                    pending = null
                    scope.launch {
                        loading = true
                        message = try {
                            val token = store.token() ?: error("missing token")
                            withContext(Dispatchers.IO) { ApiClient(base, token).post(item.path) }
                            refresh++
                            actionOk
                        } catch (_: Exception) { connectionError }
                        loading = false
                    }
                }) { Text(stringResource(R.string.confirm)) }
            }
        )
    }
}

@Composable
private fun SettingsPage(store: SecureStore, currentBase: String, onSaved: (String, Boolean) -> Unit) {
    var address by remember(currentBase) { mutableStateOf(currentBase) }
    var enteredToken by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OutlinedTextField(value = address, onValueChange = { address = it },
            label = { Text(stringResource(R.string.server_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = enteredToken, onValueChange = { enteredToken = it },
            label = { Text(stringResource(R.string.token)) },
            placeholder = { Text(stringResource(R.string.token_hint)) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        if (error) Text(stringResource(R.string.url_error), color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            error = try {
                if (!store.saveServerUrl(address)) true else {
                    if (enteredToken.isNotBlank()) store.saveToken(enteredToken.trim())
                    enteredToken = ""
                    onSaved(store.serverUrl(), store.token() != null)
                    false
                }
            } catch (_: Exception) { true }
        }) { Text(stringResource(R.string.save)) }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SummaryPage(data: JSONObject) {
    val disk = data.optJSONObject("disk") ?: JSONObject()
    val entries = remember(data) {
        buildList {
            val keys = disk.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                add(key to disk.optString(key, "-"))
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { Metric(stringResource(R.string.status), data.optString("durum", "-")) }
        item { Metric(stringResource(R.string.cpu), "${data.optString("cpu", "-")}%") }
        item { Metric(stringResource(R.string.memory), "${data.optString("ram_kul", "-")} / ${data.optString("ram_top", "-")} GiB") }
        item { Metric(stringResource(R.string.swap), "${data.optString("swap", "-")} GiB") }
        item { Metric(stringResource(R.string.load), data.optString("yuk", "-")) }
        item { Metric(stringResource(R.string.temperature), "${data.optString("temp", "-")} °C") }
        item { Metric(stringResource(R.string.gpu), data.optString("gpu_kul", "-")) }
        item { Metric(stringResource(R.string.containers), data.optString("konteyner", "-")) }
        items(entries) { (path, value) -> Metric("${stringResource(R.string.disk)} $path", "$value%") }
    }
}

@Composable
private fun ContainersPage(data: JSONObject, action: (String, String, String) -> Unit) {
    val rows = data.optJSONArray("items") ?: JSONArray()
    val allowed = data.optJSONArray("actionable").strings()
    val start = stringResource(R.string.start)
    val stop = stringResource(R.string.stop)
    val restart = stringResource(R.string.restart)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (rows.length() == 0) item { Text(stringResource(R.string.no_items), Modifier.padding(16.dp)) }
        items(rows.length()) { index ->
            val row = rows.optJSONObject(index) ?: JSONObject()
            val name = row.optString("name")
            Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(name, fontWeight = FontWeight.Bold)
                    Text(row.optString("status", row.optString("state")), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (name in allowed) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionButton(R.string.start) { action(name, start, "/v1/containers/${ApiClient.segment(name)}/start") }
                            ActionButton(R.string.stop) { action(name, stop, "/v1/containers/${ApiClient.segment(name)}/stop") }
                            ActionButton(R.string.restart) { action(name, restart, "/v1/containers/${ApiClient.segment(name)}/restart") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VmsPage(data: JSONObject, action: (String, String, String) -> Unit) {
    val rows = data.optJSONArray("items") ?: JSONArray()
    val allowed = data.optJSONArray("actionable").strings()
    val start = stringResource(R.string.start)
    val shutdown = stringResource(R.string.shutdown)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (rows.length() == 0) item { Text(stringResource(R.string.no_items), Modifier.padding(16.dp)) }
        items(rows.length()) { index ->
            val row = rows.optJSONObject(index) ?: JSONObject()
            val name = row.optString("name")
            val running = row.optString("state").contains("running", ignoreCase = true)
            Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(name, fontWeight = FontWeight.Bold)
                    Text(row.optString("state"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (name in allowed) {
                        if (running) ActionButton(R.string.shutdown) { action(name, shutdown, "/v1/vms/${ApiClient.segment(name)}/shutdown") }
                        else ActionButton(R.string.start) { action(name, start, "/v1/vms/${ApiClient.segment(name)}/start") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopsPage(data: JSONObject, action: (String, String, String) -> Unit) {
    val rows = data.optJSONArray("items") ?: JSONArray()
    val start = stringResource(R.string.start)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(rows.length()) { index ->
            val row = rows.optJSONObject(index) ?: JSONObject()
            val name = row.optString("name")
            Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(name, fontWeight = FontWeight.Bold)
                    Text(stringResource(if (row.optBoolean("active")) R.string.active else R.string.inactive))
                    if (!row.optBoolean("active")) ActionButton(R.string.start) { action(name, start, "/v1/desktops/${ApiClient.segment(name)}/start") }
                }
            }
        }
    }
}

@Composable
private fun LinksPage(data: JSONObject, open: (String) -> Unit) {
    val rows = data.optJSONArray("items") ?: JSONArray()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (rows.length() == 0) item { Text(stringResource(R.string.no_items), Modifier.padding(16.dp)) }
        items(rows.length()) { index ->
            val row = rows.optJSONObject(index) ?: JSONObject()
            Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(row.optString("title"))
                    TextButton(onClick = { open(row.optString("url")) }) { Text(stringResource(R.string.open_link)) }
                }
            }
        }
    }
}

@Composable
private fun NotificationsPage(data: JSONObject) {
    val rows = data.optJSONArray("items") ?: JSONArray()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (rows.length() == 0) item { Text(stringResource(R.string.no_items), Modifier.padding(16.dp)) }
        items(rows.length()) { index ->
            val row = rows.optJSONObject(index) ?: JSONObject()
            Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(row.optString("seviye", "-"), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(row.optString("zaman", ""), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(row.optString("metin", ""))
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(stringResource(label)) }
}

private fun JSONArray?.strings(): Set<String> {
    if (this == null) return emptySet()
    return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }.toSet()
}
