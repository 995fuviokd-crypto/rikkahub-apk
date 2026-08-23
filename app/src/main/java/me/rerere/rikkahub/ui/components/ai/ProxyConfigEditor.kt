package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProxyConfig
import me.rerere.ai.provider.ProxyType
import me.rerere.rikkahub.R

/**
 * 网络代理配置编辑组件。支持 HTTP / HTTPS / SOCKS4 / SOCKS5 / 直连，
 * host/port 与可选认证。适用于单个 Provider 代理与全局代理设置。
 */
@Composable
fun ProxyConfigEditor(
    proxy: ProxyConfig?,
    onEdit: (ProxyConfig?) -> Unit,
    modifier: Modifier = Modifier,
    allowSocks4: Boolean = true,
) {
    val enabled = proxy?.enabled == true
    val current = proxy ?: ProxyConfig()

    // 全局代理不支持 SOCKS4（需 socketFactory，无法经 ProxySelector 下发）时，
    // 若已有配置为 SOCKS4 则回退为 HTTP
    val effectiveProxy = if (!allowSocks4 && current.type == ProxyType.SOCKS4) {
        current.copy(type = ProxyType.HTTP)
    } else {
        current
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.setting_proxy_enable))
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    if (on) {
                        onEdit(effectiveProxy.copy(enabled = true))
                    } else {
                        onEdit(null)
                    }
                },
            )
        }

        if (!enabled) return

        val isDirect = effectiveProxy.type == ProxyType.DIRECT
        var typeExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = typeExpanded,
            onExpandedChange = { typeExpanded = it },
        ) {
            OutlinedTextField(
                value = proxyTypeLabel(effectiveProxy.type),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.setting_proxy_type)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = typeExpanded,
                onDismissRequest = { typeExpanded = false },
            ) {
                val types = if (allowSocks4) {
                    ProxyType.entries
                } else {
                    ProxyType.entries.filter { it != ProxyType.SOCKS4 }
                }
                types.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(proxyTypeLabel(type)) },
                        onClick = {
                            onEdit(effectiveProxy.copy(type = type))
                            typeExpanded = false
                        },
                    )
                }
            }
        }

        if (isDirect) {
            Text(
                text = stringResource(R.string.setting_proxy_direct_hint),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        OutlinedTextField(
            value = effectiveProxy.host,
            onValueChange = { onEdit(effectiveProxy.copy(host = it.trim())) },
            label = { Text(stringResource(R.string.setting_proxy_host)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = if (effectiveProxy.port == 0) "" else effectiveProxy.port.toString(),
            onValueChange = { value ->
                val port = value.filter { it.isDigit() }.toIntOrNull() ?: 0
                onEdit(effectiveProxy.copy(port = port.coerceIn(0, 65535)))
            },
            label = { Text(stringResource(R.string.setting_proxy_port)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        if (effectiveProxy.type != ProxyType.SOCKS4) {
            OutlinedTextField(
                value = effectiveProxy.username,
                onValueChange = { onEdit(effectiveProxy.copy(username = it)) },
                label = { Text(stringResource(R.string.setting_proxy_username)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            var passwordVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = effectiveProxy.password,
                onValueChange = { onEdit(effectiveProxy.copy(password = it)) },
                label = { Text(stringResource(R.string.setting_proxy_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (passwordVisible) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                },
            )
        }
    }
}

@Composable
private fun proxyTypeLabel(type: ProxyType): String = when (type) {
    ProxyType.HTTP -> stringResource(R.string.setting_proxy_type_http)
    ProxyType.HTTPS -> stringResource(R.string.setting_proxy_type_https)
    ProxyType.SOCKS4 -> stringResource(R.string.setting_proxy_type_socks4)
    ProxyType.SOCKS5 -> stringResource(R.string.setting_proxy_type_socks5)
    ProxyType.DIRECT -> stringResource(R.string.setting_proxy_type_direct)
}
