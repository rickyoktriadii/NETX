package com.netx.networktoolkit

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

data class NetworkSnapshot(val connected: Boolean, val transport: String, val latencyMs: Long?, val localAddress: String)
data class DiagnosticItem(val type: String, val target: String, val result: String, val timestamp: Long = System.currentTimeMillis())

class NetworkViewModel : ViewModel() {
    private val _network = MutableStateFlow(NetworkSnapshot(false, "Offline", null, "—"))
    val network: StateFlow<NetworkSnapshot> = _network.asStateFlow()
    private val _history = MutableStateFlow<List<DiagnosticItem>>(emptyList())
    val history: StateFlow<List<DiagnosticItem>> = _history.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refreshNetwork(context: Context) = viewModelScope.launch(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities)
        val connected = caps != null
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile Data"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> if (connected) "Connected" else "Offline"
        }
        val address = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress ?: "—"
        }.getOrDefault("—")
        val latency = runCatching {
            val start = System.nanoTime()
            if (InetAddress.getByName("1.1.1.1").isReachable(2500)) (System.nanoTime() - start) / 1_000_000 else null
        }.getOrNull()
        _network.value = NetworkSnapshot(connected, transport, latency, address)
    }

    fun ping(target: String) = viewModelScope.launch(Dispatchers.IO) {
        val clean = target.trim()
        if (clean.isBlank()) { _message.value = "Masukkan domain atau alamat IP."; return@launch }
        val samples = mutableListOf<Long>()
        repeat(4) {
            runCatching {
                val address = InetAddress.getByName(clean)
                val start = System.nanoTime()
                if (address.isReachable(2500)) samples += (System.nanoTime() - start) / 1_000_000
            }
        }
        if (samples.isEmpty()) {
            _message.value = "Ping gagal atau target tidak merespons."
        } else {
            val avg = samples.average().toLong()
            _message.value = "Ping $clean berhasil • rata-rata ${avg} ms"
            _history.value = listOf(DiagnosticItem("Ping", clean, "$avg ms")) + _history.value
        }
    }

    fun dnsLookup(domain: String) = viewModelScope.launch(Dispatchers.IO) {
        val clean = domain.trim()
        if (clean.isBlank()) { _message.value = "Masukkan domain."; return@launch }
        val result = runCatching { InetAddress.getAllByName(clean).joinToString("\n") { it.hostAddress ?: "unknown" } }
            .getOrElse { "DNS lookup gagal: ${it.message ?: "unknown error"}" }
        _message.value = result
        _history.value = listOf(DiagnosticItem("DNS", clean, result.lines().firstOrNull().orEmpty())) + _history.value
    }

    fun publicIp() = viewModelScope.launch(Dispatchers.IO) {
        val result = runCatching { URL("https://api.ipify.org").readText().trim() }
            .getOrElse { "Tidak dapat mengambil public IP: ${it.message ?: "network error"}" }
        _message.value = "Public IP: $result"
        _history.value = listOf(DiagnosticItem("Public IP", "api.ipify.org", result)) + _history.value
    }

    fun clearHistory() { _history.value = emptyList() }
    fun clearMessage() { _message.value = null }
}

private val DarkColors = darkColorScheme(primary = Color(0xFF4DA3FF), secondary = Color(0xFF38D9C5), background = Color(0xFF07111E), surface = Color(0xFF0D1A2A), surfaceVariant = Color(0xFF14243A))
private val LightColors = lightColorScheme(primary = Color(0xFF087CF2), secondary = Color(0xFF008F83), background = Color(0xFFF5F8FC), surface = Color.White, surfaceVariant = Color(0xFFE8EFF7))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { NETXApp() } }
}

@Composable
fun NETXApp(vm: NetworkViewModel = viewModel()) {
    var dark by remember { mutableStateOf(true) }
    MaterialTheme(if (dark) DarkColors else LightColors) { MainNavigation(vm, dark) { dark = !dark } }
}

@Composable
private fun MainNavigation(vm: NetworkViewModel, dark: Boolean, toggleTheme: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val network by vm.network.collectAsState()
    Scaffold(bottomBar = { NavigationBar {
        NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
        NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.History, null) }, label = { Text("History") })
        NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
    } }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        when (tab) {
            0 -> HomeScreen(vm, network, Modifier.padding(padding))
            1 -> HistoryScreen(vm, Modifier.padding(padding))
            else -> SettingsScreen(dark, toggleTheme, Modifier.padding(padding))
        }
    }
}

@Composable
private fun HomeScreen(vm: NetworkViewModel, network: NetworkSnapshot, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val message by vm.message.collectAsState()
    var target by remember { mutableStateOf("google.com") }
    var showNext by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshNetwork(context) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("NETX", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Network Diagnostic Toolkit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) { Icon(Icons.Default.NetworkCheck, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(10.dp)) }
        }
        NetworkStatusCard(network)
        OutlinedTextField(target, { target = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Target IP / Domain") }, leadingIcon = { Icon(Icons.Default.Language, null) })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ vm.ping(target) }, Modifier.weight(1f)) { Icon(Icons.Default.Bolt, null); Spacer(Modifier.width(6.dp)); Text("Ping") }
            FilledTonalButton({ vm.dnsLookup(target) }, Modifier.weight(1f)) { Icon(Icons.Default.Dns, null); Spacer(Modifier.width(6.dp)); Text("DNS") }
        }
        Text("NETWORK TOOLS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolCard("Ping", "Latency test", Icons.Default.Bolt, Modifier.weight(1f)) { vm.ping(target) }
            ToolCard("DNS Lookup", "Resolve domain", Icons.Default.Dns, Modifier.weight(1f)) { vm.dnsLookup(target) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolCard("IP Info", "Public IP", Icons.Default.Language, Modifier.weight(1f)) { vm.publicIp() }
            ToolCard("Network Details", "Connection info", Icons.Default.WifiFind, Modifier.weight(1f)) { showNext = true }
        }
        message?.let { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Text(it, Modifier.weight(1f), maxLines = 4, overflow = TextOverflow.Ellipsis); TextButton(vm::clearMessage) { Text("OK") } } } }
    }
    if (showNext) AlertDialog(onDismissRequest = { showNext = false }, title = { Text("Network Details") }, text = { Text("Versi berikutnya akan menambahkan detail Wi-Fi, gateway, DNS aktif, interface, dan traceroute secara native.") }, confirmButton = { TextButton({ showNext = false }) { Text("Tutup") } })
}

@Composable
private fun NetworkStatusCard(network: NetworkSnapshot) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(if (network.connected) Color(0xFF25D366) else Color(0xFFE74C3C), RoundedCornerShape(50)))
                Spacer(Modifier.width(8.dp)); Text(if (network.connected) "Internet Connection" else "No Connection", fontWeight = FontWeight.Bold, Modifier.weight(1f)); Icon(if (network.transport == "Wi-Fi") Icons.Default.Wifi else Icons.Default.NetworkCheck, null, tint = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Metric("Latency", network.latencyMs?.let { "$it ms" } ?: "—", Modifier.weight(1f)); Metric("Network", network.transport, Modifier.weight(1f)); Metric("Local IP", network.localAddress, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun Metric(title: String, value: String, modifier: Modifier) { Column(modifier) { Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) } }

@Composable
private fun ToolCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@Composable
private fun HistoryScreen(vm: NetworkViewModel, modifier: Modifier) {
    val history by vm.history.collectAsState()
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, Modifier.weight(1f)); if (history.isNotEmpty()) TextButton(vm::clearHistory) { Text("Clear") } }
        Spacer(Modifier.height(12.dp))
        if (history.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.History, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(8.dp)); Text("Belum ada diagnostic", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(history) { item -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (item.type == "DNS") Icons.Default.Dns else Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(item.target, fontWeight = FontWeight.SemiBold); Text("${item.type} • ${item.result}", style = MaterialTheme.typography.bodySmall) }; Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.timestamp)), style = MaterialTheme.typography.labelSmall) } } } }
    }
}

@Composable
private fun SettingsScreen(dark: Boolean, toggleTheme: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Dark Mode", fontWeight = FontWeight.SemiBold); Text("Gunakan tampilan gelap NETX", style = MaterialTheme.typography.bodySmall) }; Switch(dark, { toggleTheme() }) } }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text("NETX", fontWeight = FontWeight.Bold); Text("Network Diagnostic Toolkit"); Text("Native Android • Version 1.0.0", style = MaterialTheme.typography.bodySmall); Text("Fondasi: network status, latency, ping, DNS, public IP, history.", style = MaterialTheme.typography.bodySmall) } }
    }
}
