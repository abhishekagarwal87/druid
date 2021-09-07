/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.sql.avatica;

import org.apache.calcite.avatica.server.AbstractAvaticaHandler;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.server.DruidNode;

public class DruidAvaticaJsonHandlerTest extends DruidAvaticaHandlerTest
{
  @Override
  protected String getJdbcConnectionString(final int port)
  {
    return StringUtils.format(
            "jdbc:avatica:remote:url=http://127.0.0.1:%d%s",
            port,
            DruidAvaticaJsonHandler.AVATICA_PATH
    );
  }

  @Override
  protected String getParameterizedJdbcConnectionString(int port, String paramString)
  {
    return getJdbcConnectionString(port) + "?" + paramString;
  }

  @Override
  protected AbstractAvaticaHandler getAvaticaHandler(final DruidMeta druidMeta)
  {
    return new DruidAvaticaJsonHandler(
            druidMeta,
            new DruidNode("dummy", "dummy", false, 1, null, true, false),
            new AvaticaMonitor()
    );
  }
}
