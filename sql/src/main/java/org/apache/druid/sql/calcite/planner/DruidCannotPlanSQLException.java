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

package org.apache.druid.sql.calcite.planner;

import org.apache.calcite.plan.RelOptPlanner;
import org.apache.druid.java.util.common.StringUtils;

/**
 * This class is different from {@link org.apache.calcite.plan.RelOptPlanner.CannotPlanException} in that the error
 * messages are user-friendly unlike it's parent class. This exception class be used instead of
 * {@link org.apache.druid.java.util.common.ISE} or {@link org.apache.druid.java.util.common.IAE} when processing is
 * to be halted during planning. Similarly, Druid planner can catch these exception and know that the error
 * can be directly exposed to end-user.
 */
public class DruidCannotPlanSQLException extends RelOptPlanner.CannotPlanException
{
  public DruidCannotPlanSQLException(String formatText, Object... arguments)
  {
    super(StringUtils.nonStrictFormat(formatText, arguments));
  }
}
