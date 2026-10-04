package com.netx.networktoolkit

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NetxApp() }
    }
}

data class NetworkSnapshot(
    val connected: Boolean = false,
    val type: String = "Unknown",
    val localIp: String = "-",
    val latency: String = "-"
)

data class NetworkDetails(
    val connectionType: String,
    val localIp: String,
    val gateway: String,
    val dnsServers: List<String>,
    val interfaceName: String
)

data class HistoryItem(
    val title: String,
    val value: String,
    val time: String
)

class NetxViewModel : ViewModel() {
    private val _network = MutableStateFlow(NetworkSnapshot())
    val network: StateFlow<NetworkSnapshot> = _network.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    private val _details = MutableStateFlow<NetworkDetails?>(null)
    val details: StateFlow<NetworkDetails?> = _details.asStateFlow()

    var darkMode by mutableStateOf(true)
        private set

    fun toggleTheme() { darkMode = !darkMode }

    fun refreshNetwork(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val connected = caps != null
        val type = when {

            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile Data"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            connected -> "Other"
            else -> "Offline"
        }
        _network.value = NetworkSnapshot(connected, type, findLocalIp(), "-")
        ping("1.1.1.1")
    }

    fun ping(host: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val result = runCatching {
                InetAddress.getByName(host).isReachable(3000)
            }.getOrDefault(false)
            val elapsed = System.currentTimeMillis() - start
            val text = if (result) "${elapsed} ms" else "Timeout"
            _network.value = _network.value.copy(latency = text)
            addHistory("Ping", "$host → $text")
        }
    }

    fun dnsLookup(host: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                InetAddress.getAllByName(host).joinToString("\n") { it.hostAddress ?: "-" }
            }.getOrElse { "Lookup failed: ${it.message ?: "Unknown error"}" }
            addHistory("DNS Lookup", "$host\n$result")
        }
    }

    fun publicIp() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                URL("https://api.ipify.org").readText().trim()
            }.getOrElse { "Failed: ${it.message ?: "Unknown error"}" }
            addHistory("IP Info", "Public IP: $result")
        }
    }

    fun loadNetworkDetails(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val lp = network?.let { cm.getLinkProperties(it) }

        val connectionType = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile Data"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            caps != null -> "Other"
            else -> "Offline"
        }

        val localIp = lp?.linkAddresses
            ?.mapNotNull { it.address.hostAddress }
            ?.firstOrNull { it.contains('.') }
            ?: findLocalIp()

        val gateway = lp?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway != null }
            ?.gateway?.hostAddress ?: "-"

        val dns = lp?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?.distinct()
            ?: emptyList()

        _details.value = NetworkDetails(
            connectionType = connectionType,
            localIp = localIp,
            gateway = gateway,
            dnsServers = dns,
            interfaceName = lp?.interfaceName ?: "-"
        )
    }

    private fun findLocalIp(): String {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress ?: "-"
        }.getOrDefault("-")
    }

    private fun addHistory(title: String, value: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _history.value = listOf(HistoryItem(title, value, time)) + _history.value.take(49)
    }
}

@Composable
fun NetxApp(vm: NetxViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val network by vm.network.collectAsState()
    val history by vm.history.collectAsState()
    val details by vm.details.collectAsState()
    var showDetails by remember { mutableStateOf(false) }
    var showPing by remember { mutableStateOf(false) }
    var showDns by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf("1.1.1.1") }
    var dnsTarget by remember { mutableStateOf("google.com") }

    LaunchedEffect(Unit) { vm.refreshNetwork(context) }

    MaterialTheme(colorScheme = if (vm.darkMode) darkColorScheme() else lightColorScheme()) {
        Scaffold(
            topBar = {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("NETX", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Network Diagnostic Toolkit", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = vm.darkMode,
                        onCheckedChange = { vm.toggleTheme() }
                    )
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (network.connected) "Internet Connection" else "No Connection", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(network.type)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Latency: ${network.latency}")
                        Text("Local IP: ${network.localIp}")
                    }
                }

                Text("Diagnostics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                ToolCard("Ping", "Check host reachability and latency") { showPing = true }
                ToolCard("DNS Lookup", "Resolve a hostname to IP addresses") { showDns = true }
                ToolCard("IP Info", "Get your public IP address") { vm.publicIp() }
                ToolCard("Network Details", "Local IP, gateway, DNS and interface") {
                    vm.loadNetworkDetails(context)
                    showDetails = true
                }

                Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (history.isEmpty()) {
                    Text("No diagnostic history yet.")
                } else {
                    history.forEach {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Row {
                                    Text(it.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text(it.time, style = MaterialTheme.typography.labelSmall)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(it.value, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPing) {
        AlertDialog(
            onDismissRequest = { showPing = false },
            title = { Text("Ping") },
            text = { OutlinedTextField(target, { target = it }, label = { Text("Host or IP") }) },
            confirmButton = {
                TextButton(onClick = { showPing = false; vm.ping(target) }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { showPing = false }) { Text("Cancel") } }
        )
    }

    if (showDns) {
        AlertDialog(
            onDismissRequest = { showDns = false },
            title = { Text("DNS Lookup") },
            text = { OutlinedTextField(dnsTarget, { dnsTarget = it }, label = { Text("Hostname") }) },
            confirmButton = {
                TextButton(onClick = { showDns = false; vm.dnsLookup(dnsTarget) }) { Text("Lookup") }
            },
            dismissButton = { TextButton(onClick = { showDns = false }) { Text("Cancel") } }
        )
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("Network Details", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    details?.let {
                        DetailRow("Connection", it.connectionType)
                        DetailRow("Local IP", it.localIp)
                        DetailRow("Gateway", it.gateway)
                        DetailRow("DNS", if (it.dnsServers.isEmpty()) "-" else it.dnsServers.joinToString("\n"))
                        DetailRow("Interface", it.interfaceName)
                    } ?: Text("Loading network details...")
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun ToolCard(title: String, description: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
