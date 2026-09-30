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

package indi.dmzz_yyhyy.lightnovelreader.data.userdata

/**
 * LNR 引入适配：`UserDataRepository` 的最小接口。
 *
 * LNR 原实现 `class UserDataRepository @Inject constructor(userDataDao: UserDataDao)`
 * 直接把 8 个包装类绑到 Room DAO 上，而 AGENTS.md 4.3 明确「不引入 Room」。
 * 这里改成对 API 子集已有的
 * [io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi] 的类型别名：
 * reader 用到的 8 个工厂方法（string / float / int / boolean / intList /
 * stringList / color / uri）与 `remove` 签名完全一致，P3 由
 * `AppDataStore` 实现同一组方法即可，数据落在唯一那个
 * `preferencesDataStore(name = "wenku8_settings")` 实例上。
 */
typealias UserDataRepository = io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
