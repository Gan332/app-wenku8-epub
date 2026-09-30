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

package indi.dmzz_yyhyy.lightnovelreader.utils

import io.nightfish.lightnovelreader.api.identifier.Identifier

/**
 * LNR 引入适配：给组件 id 打上 `lightnovelreader` 命名空间前缀。
 *
 * 上游 `internal fun String.ofId() = Identifier("lightnovelreader", this)`，
 * `ErrorContentComponentRender` 用它构造 `ErrorContentComponentData.id`。
 * 保持同样语义，这样拷贝过来的组件 id 与 LNR 一致。
 */
internal fun String.ofId() = Identifier("lightnovelreader", this)
