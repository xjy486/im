package com.jitong.im.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.ArrowBack

internal enum class JitongTab(val label: String, val icon: ImageVector) {
    Messages("消息", Icons.Outlined.ChatBubbleOutline),
    Contacts("联系人", Icons.Outlined.Contacts),
    Groups("群聊", Icons.Outlined.Groups),
    Me("我的", Icons.Outlined.Person),
}

@Composable
internal fun JitongBottomBar(
    selected: JitongTab,
    onSelected: (JitongTab) -> Unit,
    unreadCount: Int = 0,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        JitongTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelected(tab) },
                icon = {
                    Box {
                        Icon(tab.icon, contentDescription = tab.label)
                        if (tab == JitongTab.Messages && unreadCount > 0) {
                            UnreadBadge(
                                count = unreadCount,
                                modifier = Modifier.align(Alignment.TopEnd),
                            )
                        }
                    }
                },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = JitongColors.blue,
                    selectedTextColor = JitongColors.blue,
                    indicatorColor = Color.Transparent,
                    unselectedIconColor = JitongColors.text,
                    unselectedTextColor = JitongColors.text,
                ),
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun JitongHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    navigationContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = JitongColors.header,
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = JitongColors.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            navigationIcon = {
                when {
                    navigationContent != null -> navigationContent()
                    onBack != null -> IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = JitongColors.header,
                titleContentColor = JitongColors.text,
                navigationIconContentColor = JitongColors.text,
                actionIconContentColor = JitongColors.text,
            ),
        )
    }
}

@Composable
internal fun JitongSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "搜索",
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = onClick != null) { onClick?.invoke() },
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = JitongColors.secondaryText)
            if (onClick == null) {
                androidx.compose.material3.OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text(placeholder, color = JitongColors.tertiaryText) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                    ),
                )
            } else {
                Text(placeholder, color = JitongColors.secondaryText, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = JitongColors.secondaryText,
    )
}

@Composable
internal fun ListDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        color = JitongColors.divider,
    )
}

@Composable
internal fun StatusDot(
    color: Color = JitongColors.success,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
internal fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier
            .clip(CircleShape)
            .background(JitongColors.danger)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun AvatarPlaceholder(
    fallback: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 52.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            fallback.take(2),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
internal fun SoftCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

@Composable
internal fun OutlinePill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = JitongColors.secondaryText,
) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, JitongColors.divider, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}
