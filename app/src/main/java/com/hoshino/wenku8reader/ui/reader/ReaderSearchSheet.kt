package com.hoshino.wenku8reader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoshino.wenku8reader.R

/**
 * 卷内与全本正文搜索弹层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSearchSheet(
    state: ReaderSearchState,
    onQueryChange: (String) -> Unit,
    onScopeChange: (SearchScope) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onSelectMatch: (SearchMatch) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            // 顶栏标题与关闭
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "正文搜索",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                }
            }

            Spacer(Modifier.height(8.dp))

            // 搜索输入框
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("输入关键词搜索正文...") },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Filled.Clear, contentDescription = "清除")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                shape = RoundedCornerShape(14.dp),
            )

            Spacer(Modifier.height(10.dp))

            // 搜索范围切换：当前章节 vs 全本搜索
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.scope == SearchScope.CURRENT_CHAPTER,
                    onClick = { onScopeChange(SearchScope.CURRENT_CHAPTER) },
                    label = { Text("当前章节") },
                    shape = RoundedCornerShape(10.dp),
                )
                FilterChip(
                    selected = state.scope == SearchScope.FULL_BOOK,
                    onClick = { onScopeChange(SearchScope.FULL_BOOK) },
                    label = { Text("全本搜索") },
                    shape = RoundedCornerShape(10.dp),
                )
            }

            // 正在搜索进度条
            if (state.isSearching) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                if (state.scope == SearchScope.FULL_BOOK && state.totalToSearch > 0) {
                    Text(
                        text = "正在检索全本：${state.searchedCount}/${state.totalToSearch} 章...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 搜索结果列表
            if (!state.isSearching && state.query.isNotBlank()) {
                if (state.matches.isEmpty()) {
                    Text(
                        text = "未找到与 \"${state.query}\" 相关的正文内容",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    Text(
                        text = "共找到 ${state.matches.size} 处匹配：",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.matches) { match ->
                            SearchMatchCard(
                                match = match,
                                query = state.query,
                                onClick = { onSelectMatch(match) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchMatchCard(
    match: SearchMatch,
    query: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            // 章节标题
            Text(
                text = match.chapterTitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(4.dp))

            // 上下文片段（高亮关键词）
            val highlightedSnippet = buildAnnotatedString {
                val snip = match.snippet
                val lowerSnip = snip.lowercase()
                val lowerQuery = query.lowercase()
                var cur = 0
                while (cur < snip.length) {
                    val matchIdx = lowerSnip.indexOf(lowerQuery, cur)
                    if (matchIdx < 0) {
                        append(snip.substring(cur))
                        break
                    }
                    if (matchIdx > cur) {
                        append(snip.substring(cur, matchIdx))
                    }
                    withStyle(
                        SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    ) {
                        append(snip.substring(matchIdx, matchIdx + query.length))
                    }
                    cur = matchIdx + query.length
                }
            }

            Text(
                text = highlightedSnippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp,
            )
        }
    }
}
