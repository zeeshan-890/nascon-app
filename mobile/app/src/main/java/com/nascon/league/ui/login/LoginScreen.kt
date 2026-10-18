package com.nascon.league.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.data.fake.DemoAccounts
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.AlertTone
import com.nascon.league.ui.components.ArenaTextField
import com.nascon.league.ui.components.InlineAlert
import com.nascon.league.ui.components.formatCountdown
import com.nascon.league.ui.components.rememberCountdown
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.navigation.ArenaLogo
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing

@Composable
fun LoginScreen(
    onLoggedIn: (roleLabel: String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    sessionExpired: Boolean = false,
) {
    var expiredAcknowledged by rememberSaveable { mutableStateOf(false) }
    if (sessionExpired && !expiredAcknowledged) {
        SessionExpiredPanel(onSignInAgain = { expiredAcknowledged = true }, modifier = modifier)
        return
    }
    val container = LocalAppContainer.current
    val vm: LoginViewModel = viewModel(factory = LoginViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val colors = Arena.colors

    LaunchedEffect(Unit) { vm.loggedIn.collect { onLoggedIn(it) } }
    LaunchedEffect(state.focusRequest) {
        when (state.focusRequest) {
            LoginField.EMAIL -> emailFocus.requestFocus()
            LoginField.PASSWORD -> passwordFocus.requestFocus()
            null -> return@LaunchedEffect
        }
        vm.focusHandled()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                .background(colors.headerBrush),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.lg, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ArenaLogo(Modifier.size(56.dp))
                    Text(
                        stringResource(R.string.ui_login_brand),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 3.sp,
                    )
                }
                val welcome = stringResource(R.string.ui_login_welcome)
                val back = stringResource(R.string.ui_login_back)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Normal)) { append(welcome) }
                        append("\n")
                        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(back) }
                    },
                    color = Color.White,
                    fontSize = 28.sp,
                    lineHeight = 42.sp,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            ArenaTextField(
                value = state.email,
                onValueChange = vm::onEmailChange,
                label = "Email",
                leadingIcon = Icons.Default.Email,
                enabled = !state.loading,
                isError = state.emailError != null,
                errorText = state.emailError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(emailFocus),
            )
            ArenaTextField(
                value = state.password,
                onValueChange = vm::onPasswordChange,
                label = "Password",
                leadingIcon = Icons.Default.Lock,
                enabled = !state.loading,
                isError = state.passwordError != null,
                errorText = state.passwordError,
                visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    focusManager.clearFocus()
                    vm.submit()
                }),
                trailing = {
                    val desc = stringResource(if (state.passwordVisible) R.string.ui_login_hide_pw_desc else R.string.ui_login_show_pw_desc)
                    TextButton(
                        onClick = vm::togglePasswordVisible,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = desc },
                    ) {
                        Text(
                            stringResource(if (state.passwordVisible) R.string.ui_login_hide else R.string.ui_login_show),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(passwordFocus),
            )

            val lockLeft = rememberCountdown(state.lockedForSeconds, key = state.lockId)
            LaunchedEffect(lockLeft, state.failureKind) {
                if (state.failureKind == LoginFailureKind.LOCKED && state.lockedForSeconds != null && lockLeft == 0) vm.lockExpired()
            }
            val locked = (lockLeft ?: 0) > 0
            state.errorMessage?.let { msg ->
                val attempts = state.attemptsLeft?.let {
                    if (it == 1) stringResource(R.string.st_login_attempts_one) else stringResource(R.string.st_login_attempts_many, it)
                }
                InlineAlert(
                    text = if (attempts != null) "$msg $attempts" else msg,
                    tone = if (state.failureKind == LoginFailureKind.LOCKED) AlertTone.WARNING else AlertTone.ERROR,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                ) {
                    if (state.serverUnreachable) {
                        SecondaryButton(
                            text = stringResource(R.string.st_login_server_settings),
                            onClick = onOpenSettings,
                            icon = Icons.Default.Settings,
                        )
                    }
                }
            }

            PrimaryButton(
                text = if (locked) stringResource(R.string.st_try_again_in, formatCountdown(lockLeft ?: 0))
                else stringResource(R.string.st_login_sign_in),
                enabled = !locked,
                onClick = {
                    focusManager.clearFocus()
                    vm.submit()
                },
                modifier = Modifier.fillMaxWidth(),
                loading = state.loading,
            )

            ArenaCard(modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = colors.brand)
                    Text(
                        stringResource(R.string.ui_login_no_account),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkSoft,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            ArenaCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics { role = Role.Button }
                        .clickable(onClick = vm::toggleDemo),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Demo accounts",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (state.demoExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (state.demoExpanded) "Collapse" else "Expand",
                    )
                }
                if (state.demoExpanded) {
                    DemoAccounts.all.forEach { acc ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .semantics { role = Role.Button }
                                .clickable(enabled = !state.loading) { vm.fillDemo(acc) }
                                .padding(vertical = Spacing.sm),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(acc.roleLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(acc.email, style = MaterialTheme.typography.bodyMedium, color = colors.inkSoft)
                        }
                    }
                    Text(
                        stringResource(R.string.f10a_demo_password_hint, DemoAccounts.PASSWORD),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Tap an account to fill the form.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkSoft,
                    )
                }
            }

            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.ui_login_server_settings),
                    modifier = Modifier.padding(start = Spacing.sm),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** A07: shown instead of the form after SessionEvent.Expired until the user taps Sign in again. */
@Composable
private fun SessionExpiredPanel(onSignInAgain: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Arena.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                .background(colors.headerBrush),
        ) {
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                ArenaLogo(Modifier.size(40.dp))
                Text(
                    stringResource(R.string.ui_login_brand),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 3.sp,
                )
            }
        }
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(horizontal = 40.dp, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = colors.brand, modifier = Modifier.size(72.dp))
            Text(
                stringResource(R.string.st_session_title),
                color = colors.ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.st_session_message),
                color = colors.inkSoft,
                fontSize = 13.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            PrimaryButton(text = stringResource(R.string.st_session_sign_in_again), onClick = onSignInAgain)
        }
    }
}
