package com.brunogiovani.cachetaburaco.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brunogiovani.cachetaburaco.R
import kotlinx.coroutines.delay

/**
 * Componentes visuais reutilizados pelas telas de fora da partida (login, menu,
 * lobby, perfil e ranking). Nada aqui guarda regra de negocio - so aparencia e
 * pequenas animacoes, para as telas nao duplicarem o mesmo Box+Image+gradiente
 * cinco vezes.
 */

@Composable
fun MenuBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.table_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MenuColors.backgroundScrim())
        )
        content()
    }
}

@Composable
fun MenuTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = MenuMetrics.MinTouchTarget)) {
            Text("Voltar", color = MenuColors.OnDark.copy(alpha = 0.85f), fontWeight = FontWeight.SemiBold)
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = MenuColors.Gold,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = MenuColors.OnDarkMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(modifier = Modifier.width(64.dp))
    }
}

/** Botao de acao com leve "afundada" ao toque - unico feedback de pressao do app. */
@Composable
fun MenuPressScale(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = MenuMotion.quick(),
        label = "menu_press_scale"
    )
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
    ) {
        content()
    }
}

/**
 * Linha de acao do menu principal: card neutro com um "selo" colorido a
 * esquerda. Quando [highlighted] a cor de destaque preenche o card inteiro -
 * usado so na acao principal da tela, para nao apagar as demais opcoes.
 */
@Composable
fun MenuActionRow(
    title: String,
    subtitle: String,
    glyph: String,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    MenuPressScale(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth()
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (highlighted) accentColor.copy(alpha = 0.94f) else MenuColors.InkPanel
            ),
            shape = MenuShapes.Card,
            elevation = CardDefaults.cardElevation(if (highlighted) 6.dp else 2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (compact) 62.dp else 72.dp)
                .border(
                    width = 1.dp,
                    color = if (highlighted) Color.Transparent else accentColor.copy(alpha = 0.4f),
                    shape = MenuShapes.Card
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (compact) 12.dp else 14.dp,
                        vertical = if (compact) 8.dp else 12.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(if (compact) 36.dp else 42.dp)
                        .background(
                            if (highlighted) Color.White.copy(alpha = 0.22f) else accentColor.copy(alpha = 0.18f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        glyph,
                        fontSize = if (compact) 17.sp else 19.sp,
                        color = if (highlighted) Color.White else accentColor
                    )
                }
                Spacer(modifier = Modifier.width(if (compact) 10.dp else 12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (badge != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            MenuBadge(text = badge, color = if (highlighted) Color.White else MenuColors.Gold)
                        }
                    }
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = if (highlighted) 0.86f else 0.6f),
                        fontSize = 11.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Botao de acao simples (formularios: login, perfil, ranking) - largura total,
 * com o mesmo feedback de pressao dos demais controles do menu. */
@Composable
fun MenuFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MenuColors.TableGreenLight,
    contentColor: Color = Color.White,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    MenuPressScale(
        onClick = onClick,
        enabled = enabled && !loading,
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .background(
                    if (enabled) containerColor.copy(alpha = 0.96f) else containerColor.copy(alpha = 0.32f),
                    MenuShapes.Button
                ),
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                CircularProgressIndicator(color = contentColor, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            } else {
                Text(text, color = contentColor, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun MenuBadge(text: String, modifier: Modifier = Modifier, color: Color = MenuColors.Gold) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.22f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** Numero pequeno antes do titulo - da a sensacao de passo a passo (1, 2, 3...)
 * sem precisar de uma tela de wizard separada. */
@Composable
private fun MenuStepBadge(stepNumber: Int) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(MenuColors.TableGreenLight.copy(alpha = 0.22f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stepNumber.toString(),
            color = MenuColors.TableGreenLight,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun MenuSectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    stepNumber: Int? = null,
    containerColor: Color = MenuColors.InkPanel,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = MenuShapes.Card,
        elevation = CardDefaults.cardElevation(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MenuColors.Border, MenuShapes.Card)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (title != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 10.dp)
                ) {
                    if (stepNumber != null) {
                        MenuStepBadge(stepNumber)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = title,
                        color = MenuColors.OnDark.copy(alpha = 0.88f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }
            content()
        }
    }
}

/** Secao recolhivel usada nas opcoes menos criticas do lobby. Comeca aberta -
 * ninguem perde uma configuracao so porque a secao guarda ela recolhida. */
@Composable
fun MenuExpandableSection(
    title: String,
    modifier: Modifier = Modifier,
    stepNumber: Int? = null,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MenuMotion.standard(),
        label = "menu_chevron"
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = MenuColors.InkPanel),
        shape = MenuShapes.Card,
        elevation = CardDefaults.cardElevation(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MenuColors.Border, MenuShapes.Card)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MenuMetrics.MinTouchTarget)
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (stepNumber != null) {
                        MenuStepBadge(stepNumber)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(title, color = MenuColors.OnDark.copy(alpha = 0.88f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Text(
                    text = "▾",
                    color = MenuColors.OnDarkMuted,
                    fontSize = 18.sp,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation }
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(MenuMotion.standard()) + fadeIn(MenuMotion.standard()),
                exit = shrinkVertically(MenuMotion.quick()) + fadeOut(MenuMotion.quick())
            ) {
                Column(modifier = Modifier.padding(top = 10.dp), content = content)
            }
        }
    }
}

/** Rotulo pequeno para separar subgrupos dentro de uma secao, sem precisar
 * de um novo card (regra do brief: nada de card dentro de card). */
@Composable
fun MenuGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = MenuColors.TableGreenLight.copy(alpha = 0.85f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@Composable
fun MenuToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MenuMetrics.MinTouchTarget)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = MenuColors.OnDark, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(description, color = MenuColors.OnDarkFaint, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MenuColors.TableGreenLight,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color.White.copy(alpha = 0.3f)
            )
        )
    }
}

@Composable
fun MenuChipOption(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    MenuPressScale(onClick = onClick, modifier = modifier) {
        Box(
            modifier = Modifier
                .heightIn(min = MenuMetrics.MinTouchTarget)
                .border(
                    width = 1.5.dp,
                    color = if (isSelected) MenuColors.TableGreenLight else Color.White.copy(alpha = 0.25f),
                    shape = MenuShapes.Chip
                )
                .background(
                    if (isSelected) MenuColors.TableGreenLight.copy(alpha = 0.2f) else Color.Transparent,
                    MenuShapes.Chip
                )
                .padding(horizontal = 16.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (isSelected) MenuColors.TableGreenLight else Color.White.copy(alpha = 0.75f),
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 13.sp
            )
        }
    }
}

/** Cartao de opcao maior (tipo de jogo, nivel do bot) com titulo + descricao. */
@Composable
fun MenuOptionTile(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MenuColors.TableGreenLight,
    minHeight: Dp = 68.dp
) {
    MenuPressScale(onClick = onClick, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) accentColor else Color.White.copy(alpha = 0.18f),
                    shape = MenuShapes.Card
                )
                .background(
                    if (isSelected) accentColor.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f),
                    MenuShapes.Card
                )
                .padding(vertical = 12.dp, horizontal = 10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = if (isSelected) accentColor else MenuColors.OnDark,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = description,
                color = Color.White.copy(alpha = if (isSelected) 0.85f else 0.6f),
                fontSize = 10.5.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Entrada suave (fade + leve subida) para uma secao aparecer sem "estalar" na tela. */
@Composable
fun MenuEntrance(
    modifier: Modifier = Modifier,
    delayMillis: Int = 0,
    content: @Composable () -> Unit
) {
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) {
        if (delayMillis > 0) delay(delayMillis.toLong())
        state.targetState = true
    }
    AnimatedVisibility(
        visibleState = state,
        modifier = modifier,
        enter = fadeIn(MenuMotion.standard()) + slideInVertically(MenuMotion.standard()) { it / 6 }
    ) {
        content()
    }
}

@Composable
fun MenuStatusMessage(
    text: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    showSpinner: Boolean = true,
    tint: Color = MenuColors.OnDarkMuted
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showSpinner) {
            CircularProgressIndicator(color = MenuColors.Gold, strokeWidth = 3.dp)
            Spacer(modifier = Modifier.height(14.dp))
        }
        Text(text, color = MenuColors.OnDark.copy(alpha = 0.85f), fontSize = 14.sp, textAlign = TextAlign.Center)
        if (caption != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(caption, color = tint, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF10161C)
@Composable
private fun MenuComponentsPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MenuActionRow(
                title = "Criar sala local",
                subtitle = "Configure regras para jogar na mesma rede Wi-Fi",
                glyph = "♠",
                accentColor = MenuColors.TableGreenLight,
                onClick = {},
                highlighted = true
            )
            MenuActionRow(
                title = "Encontrar sala online",
                subtitle = "Veja as regras antes de escolher uma mesa",
                glyph = "◈",
                accentColor = MenuColors.Gold,
                onClick = {},
                badge = "BETA"
            )
            MenuSectionCard(title = "Exemplo de secao") {
                MenuToggleRow("Ordenar mao", "Organiza por naipe", true, {})
            }
        }
    }
}
