package com.c2c

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.c2c.data.local.CommandEntity
import com.c2c.ui.theme.*
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

class MainActivity : FragmentActivity() {
    private val viewModel: CoreViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val eglContext = viewModel.webRtcManager.getEglBaseContext()

        setContent { 
            PremiumTheme { 
                AppNavigation(this, viewModel, eglContext) 
            } 
        }
    }

    fun promptAuth(onSuccess: () -> Unit) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("System Authentication")
            .setSubtitle("Identity required")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() { 
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { onSuccess() } 
        }).authenticate(promptInfo)
    }
}

@Composable
fun AppNavigation(activity: MainActivity, viewModel: CoreViewModel, eglContext: EglBase.Context) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "login") {
        composable("login") { 
            LoginScreen({ navController.navigate("main") { popUpTo("login") { inclusive = true } } }, activity) 
        }
        composable("main") { 
            MainScreen(viewModel, eglContext) 
        }
    }
}

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit, activity: MainActivity) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Fingerprint, "Auth", tint = ActionBlue, modifier = Modifier.size(80.dp)); Spacer(modifier = Modifier.height(24.dp))
        Text("CORE SYSTEM", fontSize = 28.sp, fontWeight = FontWeight.Light, color = TextPrimary, letterSpacing = 2.sp, fontFamily = UbuntuFont); Spacer(modifier = Modifier.height(48.dp))
        Button(onClick = { activity.promptAuth(onLoginSuccess) }, colors = ButtonDefaults.buttonColors(containerColor = GlassSurface, contentColor = ActionBlue), modifier = Modifier.height(56.dp).fillMaxWidth()) {
            Icon(Icons.Rounded.LockOpen, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(8.dp))
            Text("AUTHENTICATE", fontWeight = FontWeight.Medium, fontFamily = UbuntuFont)
        }
    }
}

@Composable
fun MainScreen(viewModel: CoreViewModel, eglContext: EglBase.Context) {
    var selectedTab by remember { mutableStateOf(0) }
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Color(0x990A0A0F)) {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Rounded.Search, null) }, label = { Text("COMMAND HUB") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Rounded.Chat, null) }, label = { Text("TG CLONE") })
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Rounded.Dns, null) }, label = { Text("SERVER") })
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> CommandHub(viewModel)
                1 -> TgPager(viewModel)
                2 -> ServerPager(viewModel, eglContext)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommandHub(viewModel: CoreViewModel) {
    val commands by viewModel.commands.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var commandToEdit by remember { mutableStateOf<CommandEntity?>(null) }

    val filteredCommands = commands.filter { it.label.contains(searchQuery, ignoreCase = true) }

    if (showAddDialog || commandToEdit != null) {
        CommandEditorDialog(
            command = commandToEdit,
            onDismiss = { showAddDialog = false; commandToEdit = null },
            onSave = { label, cmd, arg, icon -> 
                viewModel.addCommand(label, cmd, arg, icon)
                showAddDialog = false; commandToEdit = null
            },
            onDelete = {
                commandToEdit?.let { viewModel.deleteCommand(it) }
                showAddDialog = false; commandToEdit = null
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            placeholder = { Text("Search directives...") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = GlassSurface, unfocusedContainerColor = GlassSurface)
        )
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filteredCommands, key = { it.id }) { cmd ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).combinedClickable(
                        onClick = { viewModel.executeCommand(cmd.cmd, cmd.defaultArg) },
                        onLongClick = { commandToEdit = cmd }
                    ), colors = CardDefaults.cardColors(containerColor = GlassSurface)
                ) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(getIconByName(cmd.icon), null, tint = ActionBlue)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(cmd.label, color = TextPrimary)
                            if (cmd.defaultArg.isNotBlank()) Text("Arg: ${cmd.defaultArg}", color = PremiumTeal, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        FloatingActionButton(onClick = { showAddDialog = true }, modifier = Modifier.padding(24.dp), containerColor = ActionBlue) { Icon(Icons.Rounded.Add, null) }
    }
}

@Composable
fun CommandEditorDialog(command: CommandEntity?, onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit, onDelete: () -> Unit) {
    var label by remember { mutableStateOf(command?.label ?: "") }
    var cmd by remember { mutableStateOf(command?.cmd ?: "") }
    var arg by remember { mutableStateOf(command?.defaultArg ?: "") }
    var icon by remember { mutableStateOf(command?.icon ?: "code") }
    
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(if (command == null) "New Command" else "Edit Command") },
        text = {
            Column {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label") })
                OutlinedTextField(value = cmd, onValueChange = { cmd = it }, label = { Text("Command (e.g. loc)") })
                OutlinedTextField(value = arg, onValueChange = { arg = it }, label = { Text("Argument (Optional)") })
                OutlinedTextField(value = icon, onValueChange = { icon = it }, label = { Text("Icon string") })
            }
        },
        confirmButton = { Button(onClick = { onSave(label, cmd, arg, icon) }) { Text("SAVE") } },
        dismissButton = {
            Row {
                if (command != null) TextButton(onClick = onDelete) { Text("DELETE", color = ErrorRed) }
                TextButton(onClick = onDismiss) { Text("CANCEL") }
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TgPager(viewModel: CoreViewModel) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    Column {
        TabRow(selectedTabIndex = pagerState.currentPage, containerColor = Color.Transparent, contentColor = PremiumTeal, indicator = { tabPositions -> TabRowDefaults.Indicator(Modifier.tabIndicatorOffset(tabPo