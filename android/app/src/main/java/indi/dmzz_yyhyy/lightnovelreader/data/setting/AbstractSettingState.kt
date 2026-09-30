/*
 * Copyright (C) 2025 走路 simply
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package indi.dmzz_yyhyy.lightnovelreader.data.setting

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.StateFactoryMarker
import io.nightfish.lightnovelreader.api.userdata.UserData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * LNR 引入适配：原实现只包一层 `getFlowWithDefault().collect`，本工程在 P3 会把
 * `UserData` 的存储源从 Room 换成 DataStore。这里保持完全一致的语义，
 * 让 [indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.SettingState] 可以原样拷贝。
 */
abstract class AbstractSettingState(
    private val coroutineScope: CoroutineScope,
) {

    @StateFactoryMarker
    protected fun <T> UserData<T>.asState(initial: T): State<T> =
        observeAsState(initial)

    @StateFactoryMarker
    protected fun <T> UserData<T>.safeAsState(initial: T): State<T> =
        observeAsState(initial)

    private fun <T> UserData<T>.observeAsState(initial: T): State<T> {
        val state = mutableStateOf(initial)
        coroutineScope.launch(Dispatchers.IO) {
            getFlowWithDefault(initial).collect { state.value = it }
        }
        return state
    }
}
