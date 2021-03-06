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

import com.facebook.presto.operator.aggregation.state.InitialLongValue;
import com.facebook.presto.operator.aggregation.state.NullableBigintState;
import com.facebook.presto.spi.type.BigintType;
import com.facebook.presto.type.SqlType;

@AggregationFunction("max")
public final class LongMaxAggregation
{
    public static final InternalAggregationFunction LONG_MAX = new AggregationCompiler().generateAggregationFunction(LongMaxAggregation.class);

    private LongMaxAggregation() {}

    @InputFunction
    @IntermediateInputFunction
    public static void max(BigintMaxState state, @SqlType(BigintType.NAME) long value)
    {
        state.setNull(false);
        state.setLong(Math.max(state.getLong(), value));
    }

    public interface BigintMaxState
            extends NullableBigintState
    {
        @Override
        @InitialLongValue(Long.MIN_VALUE)
        long getLong();
    }
}
