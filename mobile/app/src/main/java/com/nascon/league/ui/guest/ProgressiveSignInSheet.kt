package com.nascon.league.ui.guest

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nascon.league.R
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressiveSignInSheet(
    visible: Boolean,
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    onClose: () -> Unit,
) {
    if (!visible) return
    ModalBottomSheet(onDismissRequest = onClose, modifier = Modifier.testTag("progressive_sheet")) {
        val login = stringResource(R.string.gs_login)
        val register = stringResource(R.string.gs_register)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md).padding(bottom = Spacing.lg),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                stringResource(R.string.gs_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.gs_sheet_body), style = MaterialTheme.typography.bodyMedium)
            PrimaryButton(
                text = login,
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = login },
            )
            SecondaryButton(
                text = register,
                onClick = onRegister,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = register },
            )
            val closeCd = stringResource(R.string.gs_close_cd)
            TextButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = closeCd },
            ) { Text(stringResource(R.string.gs_close)) }
        }
    }
}
