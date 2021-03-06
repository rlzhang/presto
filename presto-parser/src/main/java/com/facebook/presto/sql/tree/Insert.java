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
package com.facebook.presto.sql.tree;

import com.google.common.base.Objects;

import static com.google.common.base.Preconditions.checkNotNull;

public final class Insert
        extends Statement
{
    private final QualifiedName target;
    private final Query query;

    public Insert(QualifiedName target, Query query)
    {
        this.target = checkNotNull(target, "target is null");
        this.query = checkNotNull(query, "query is null");
    }

    public QualifiedName getTarget()
    {
        return target;
    }

    public Query getQuery()
    {
        return query;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context)
    {
        return visitor.visitInsert(this, context);
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(target, query);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if ((obj == null) || (getClass() != obj.getClass())) {
            return false;
        }
        Insert o = (Insert) obj;
        return Objects.equal(target, o.target) &&
                Objects.equal(query, o.query);
    }

    @Override
    public String toString()
    {
        return Objects.toStringHelper(this)
                .add("target", target)
                .add("query", query)
                .toString();
    }
}
