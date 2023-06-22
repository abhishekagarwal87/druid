# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#!/bin/bash
set -e
changed_java_files=$1

file_list="file:pom.xml"

if [ $(echo "$changed_java_files" | wc -l) -gt 50 ]; then
  echo "Scanning all java files"
  file_list="${file_list}||file:*.java"
else
  for file in $changed_java_files; do
    file_list="${file_list}||file:${file}"
  done
fi

echo "${file_list}"

scope_content="
<component name=\"DependencyValidationManager\">
  <scope name=\"CustomScope\" pattern=\"${file_list}\" />
</component>
"

echo "$scope_content" > $(pwd)/.idea/scopes/CustomScope.xml

docker run --rm \
          -v $(pwd):/project \
          -v ~/.m2:/home/inspect/.m2 \
          -v $(pwd)/.idea/misc-for-inspection.xml:/project/.idea/misc.xml \
          ccaominh/intellij-inspect:1.0.0 \
          /project/pom.xml \
          /project/.idea/inspectionProfiles/Druid.xml \
          --levels ERROR \
          --scope CustomScope
