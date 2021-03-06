/*
 * Copyright 2011-2012 the original author or authors.
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

package org.vertx.java.core.deploy.impl.rhino;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;

/**
 * We need to make sure any Java primitive types are passed into Rhino code as the corresponding JS types
 * By default Rhino doesn't do this.
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class RhinoContextFactory extends ContextFactory {

  protected void onContextCreated(Context cx) {
    super.onContextCreated(cx);
    cx.getWrapFactory().setJavaPrimitiveWrap(false);
  }
}


