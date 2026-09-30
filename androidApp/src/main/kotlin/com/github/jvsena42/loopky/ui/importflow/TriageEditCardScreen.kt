package com.github.jvsena42.loopky.ui.importflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.domain.model.frontBackOf
import com.github.jvsena42.loopky.ui.components.CardSideEditor
import com.github.jvsena42.loopky.ui.components.ImagePickerSheet
import com.github.jvsena42.loopky.ui.components.ImageSelection
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import org.koin.compose.koinInject

@Composable
fun TriageEditCardRoute(
    rowIndex: Int,
    onBack: () -> Unit = {},
    /** Opens Settings on the Unsplash key row, for when the image sheet reports a key problem. */
    onOpenSettings: () -> Unit = {},
) {
    val importRepository = koinInject<ImportRepository>()
    val currentBack by rememberUpdatedState(onBack)

    val initial = remember(rowIndex) {
        val draft = importRepository.currentDraft()
        val row = draft?.rows?.firstOrNull { it.index == rowIndex }
        if (draft != null && row != null) draft.frontBackOf(row) else "" to ""
    }
    var front by remember(rowIndex) { mutableStateOf(initial.first) }
    var back by remember(rowIndex) { mutableStateOf(initial.second) }
    var frontImage by remember(rowIndex) { mutableStateOf(importRepository.rowImage(rowIndex, isFront = true)) }
    var backImage by remember(rowIndex) { mutableStateOf(importRepository.rowImage(rowIndex, isFront = false)) }

    TriageEditCardScreen(
        front = front,
        back = back,
        frontImage = frontImage,
        backImage = backImage,
        onFrontChange = { front = it },
        onBackChange = { back = it },
        onFrontImageSelected = { img ->
            frontImage = img
            importRepository.setRowImage(rowIndex, isFront = true, image = img)
        },
        onBackImageSelected = { img ->
            backImage = img
            importRepository.setRowImage(rowIndex, isFront = false, image = img)
        },
        onCancel = currentBack,
        onSave = {
            importRepository.updateRow(rowIndex, front, back)
            currentBack()
        },
        onOpenSettings = onOpenSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriageEditCardScreen(
    front: String,
    back: String,
    frontImage: DraftCardImage?,
    backImage: DraftCardImage?,
    onFrontChange: (String) -> Unit,
    onBackChange: (String) -> Unit,
    onFrontImageSelected: (DraftCardImage?) -> Unit,
    onBackImageSelected: (DraftCardImage?) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    /** Opens Settings on the Unsplash key row, for when the image sheet reports a key problem. */
    onOpenSettings: () -> Unit = {},
) {
    val colors = LoopkyTheme.colors
    // Which side's image picker sheet is open (true = front, false = back, null = none).
    var pickerSide by remember { mutableStateOf<Boolean?>(null) }

    Scaffold(
        containerColor = colors.surfacePrimary,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.edit_card_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.edit_card_cancel),
                            tint = colors.foregroundPrimary,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = onSave,
                        modifier = Modifier.testTag("triage_edit_save"),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
                    ) {
                        Text(text = stringResource(R.string.edit_card_save), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surfacePrimary,
                    titleContentColor = colors.foregroundPrimary,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Without this the scroll container keeps its full height behind the keyboard,
                // so the field being typed into stays hidden and there is nothing left to scroll.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            CardSideEditor(
                label = stringResource(R.string.triage_front_label),
                value = front,
                onValueChange = onFrontChange,
                placeholder = stringResource(R.string.edit_card_front_placeholder),
                textStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                imageModel = frontImage?.url ?: frontImage?.bytes,
                onPickImage = { pickerSide = true },
                onRemoveImage = { onFrontImageSelected(null) },
                imageTag = "triage_edit_front_image",
                fieldTag = "triage_edit_front",
            )
            CardSideEditor(
                label = stringResource(R.string.triage_back_label),
                value = back,
                onValueChange = onBackChange,
                placeholder = stringResource(R.string.edit_card_back_placeholder),
                textStyle = TextStyle(fontSize = 16.sp),
                imageModel = backImage?.url ?: backImage?.bytes,
                onPickImage = { pickerSide = false },
                onRemoveImage = { onBackImageSelected(null) },
                imageTag = "triage_edit_back_image",
                fieldTag = "triage_edit_back",
            )
        }
    }

    pickerSide?.let { isFront ->
        ImagePickerSheet(
            onOpenSettings = onOpenSettings,
            title = stringResource(if (isFront) R.string.image_sheet_front_title else R.string.image_sheet_back_title),
            subtitle = null,
            onDismiss = { pickerSide = null },
            onSelected = { selection ->
                val img = when (selection) {
                    is ImageSelection.Web -> DraftCardImage(url = selection.url)
                    is ImageSelection.Gallery -> DraftCardImage(bytes = selection.bytes, mime = selection.mime)
                }
                if (isFront) onFrontImageSelected(img) else onBackImageSelected(img)
                pickerSide = null
            },
        )
    }
}
