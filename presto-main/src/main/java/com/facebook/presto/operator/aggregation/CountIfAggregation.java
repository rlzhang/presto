/*
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
package com.facebook.presto.operator.aggregation;

import com.facebook.presto.operator.aggregation.state.LongState;
import com.facebook.presto.spi.type.BooleanType;
import com.facebook.presto.type.SqlType;

@AggregationFunction("count_if")
public final class CountIfAggregation
{
    public static final InternalAggregationFunction COUNT_IF = new AggregationCompiler().generateAggregationFunction(CountIfAggregation.class);

    private CountIfAggregation() {}

    @InputFunction
    public static void input(LongState state, @SqlType(BooleanType.NAME) boolean value)
    {
        if (value) {
            state.setLong(state.getLong() + 1);
        }
    }

    @CombineFunction
    public static void combine(LongState state, LongState otherState)
    {
        state.setLong(state.getLong() + otherState.getLong());
    }
}
