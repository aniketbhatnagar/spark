/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.expressions

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult
import org.apache.spark.sql.catalyst.util.TypeUtils
import org.apache.spark.sql.catalyst.expressions.codegen.{GeneratedExpressionCode, CodeGenContext}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.types._

object InterpretedPredicate {
  def create(expression: Expression, inputSchema: Seq[Attribute]): (InternalRow => Boolean) =
    create(BindReferences.bindReference(expression, inputSchema))

  def create(expression: Expression): (InternalRow => Boolean) = {
    (r: InternalRow) => expression.eval(r).asInstanceOf[Boolean]
  }
}

trait Predicate extends Expression {
  self: Product =>

  override def dataType: DataType = BooleanType
}

trait PredicateHelper {
  protected def splitConjunctivePredicates(condition: Expression): Seq[Expression] = {
    condition match {
      case And(cond1, cond2) =>
        splitConjunctivePredicates(cond1) ++ splitConjunctivePredicates(cond2)
      case other => other :: Nil
    }
  }

  protected def splitDisjunctivePredicates(condition: Expression): Seq[Expression] = {
    condition match {
      case Or(cond1, cond2) =>
        splitDisjunctivePredicates(cond1) ++ splitDisjunctivePredicates(cond2)
      case other => other :: Nil
    }
  }

  /**
   * Returns true if `expr` can be evaluated using only the output of `plan`.  This method
   * can be used to determine when is is acceptable to move expression evaluation within a query
   * plan.
   *
   * For example consider a join between two relations R(a, b) and S(c, d).
   *
   * `canEvaluate(EqualTo(a,b), R)` returns `true` where as `canEvaluate(EqualTo(a,c), R)` returns
   * `false`.
   */
  protected def canEvaluate(expr: Expression, plan: LogicalPlan): Boolean =
    expr.references.subsetOf(plan.outputSet)
}

case class Not(child: Expression) extends UnaryExpression with Predicate with ExpectsInputTypes {
  override def toString: String = s"NOT $child"

  override def inputTypes: Seq[DataType] = Seq(BooleanType)

  protected override def nullSafeEval(input: Any): Any = !input.asInstanceOf[Boolean]

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, c => s"!($c)")
  }
}

/**
 * Evaluates to `true` if `list` contains `value`.
 */
case class In(value: Expression, list: Seq[Expression]) extends Predicate {
  override def children: Seq[Expression] = value +: list

  override def nullable: Boolean = true // TODO: Figure out correct nullability semantics of IN.
  override def toString: String = s"$value IN ${list.mkString("(", ",", ")")}"

  override def eval(input: InternalRow): Any = {
    val evaluatedValue = value.eval(input)
    list.exists(e => e.eval(input) == evaluatedValue)
  }
}

/**
 * Optimized version of In clause, when all filter values of In clause are
 * static.
 */
case class InSet(child: Expression, hset: Set[Any])
  extends UnaryExpression with Predicate {

  override def nullable: Boolean = true // TODO: Figure out correct nullability semantics of IN.
  override def toString: String = s"$child INSET ${hset.mkString("(", ",", ")")}"

  override def eval(input: InternalRow): Any = {
    hset.contains(child.eval(input))
  }
}

case class And(left: Expression, right: Expression)
  extends BinaryExpression with Predicate with ExpectsInputTypes {

  override def toString: String = s"($left && $right)"

  override def inputTypes: Seq[DataType] = Seq(BooleanType, BooleanType)

  override def eval(input: InternalRow): Any = {
    val input1 = left.eval(input)
    if (input1 == false) {
       false
    } else {
      val input2 = right.eval(input)
      if (input2 == false) {
        false
      } else {
        if (input1 != null && input2 != null) {
          true
        } else {
          null
        }
      }
    }
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val eval1 = left.gen(ctx)
    val eval2 = right.gen(ctx)

    // The result should be `false`, if any of them is `false` whenever the other is null or not.
    s"""
      ${eval1.code}
      boolean ${ev.isNull} = false;
      boolean ${ev.primitive} = false;

      if (!${eval1.isNull} && !${eval1.primitive}) {
      } else {
        ${eval2.code}
        if (!${eval2.isNull} && !${eval2.primitive}) {
        } else if (!${eval1.isNull} && !${eval2.isNull}) {
          ${ev.primitive} = true;
        } else {
          ${ev.isNull} = true;
        }
      }
     """
  }
}

case class Or(left: Expression, right: Expression)
  extends BinaryExpression with Predicate with ExpectsInputTypes {

  override def toString: String = s"($left || $right)"

  override def inputTypes: Seq[DataType] = Seq(BooleanType, BooleanType)

  override def eval(input: InternalRow): Any = {
    val input1 = left.eval(input)
    if (input1 == true) {
      true
    } else {
      val input2 = right.eval(input)
      if (input2 == true) {
        true
      } else {
        if (input1 != null && input2 != null) {
          false
        } else {
          null
        }
      }
    }
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val eval1 = left.gen(ctx)
    val eval2 = right.gen(ctx)

    // The result should be `true`, if any of them is `true` whenever the other is null or not.
    s"""
      ${eval1.code}
      boolean ${ev.isNull} = false;
      boolean ${ev.primitive} = true;

      if (!${eval1.isNull} && ${eval1.primitive}) {
      } else {
        ${eval2.code}
        if (!${eval2.isNull} && ${eval2.primitive}) {
        } else if (!${eval1.isNull} && !${eval2.isNull}) {
          ${ev.primitive} = false;
        } else {
          ${ev.isNull} = true;
        }
      }
     """
  }
}

abstract class BinaryComparison extends BinaryOperator with Predicate {
  self: Product =>

  override def checkInputDataTypes(): TypeCheckResult = {
    if (left.dataType != right.dataType) {
      TypeCheckResult.TypeCheckFailure(
        s"differing types in ${this.getClass.getSimpleName} " +
        s"(${left.dataType} and ${right.dataType}).")
    } else {
      checkTypesInternal(dataType)
    }
  }

  protected def checkTypesInternal(t: DataType): TypeCheckResult

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    if (ctx.isPrimitiveType(left.dataType)) {
      // faster version
      defineCodeGen(ctx, ev, (c1, c2) => s"$c1 $symbol $c2")
    } else {
      defineCodeGen(ctx, ev, (c1, c2) => s"${ctx.genComp(left.dataType, c1, c2)} $symbol 0")
    }
  }
}

private[sql] object BinaryComparison {
  def unapply(e: BinaryComparison): Option[(Expression, Expression)] = Some((e.left, e.right))
}

/** An extractor that matches both standard 3VL equality and null-safe equality. */
private[sql] object Equality {
  def unapply(e: BinaryComparison): Option[(Expression, Expression)] = e match {
    case EqualTo(l, r) => Some((l, r))
    case EqualNullSafe(l, r) => Some((l, r))
    case _ => None
  }
}

case class EqualTo(left: Expression, right: Expression) extends BinaryComparison {
  override def symbol: String = "="

  override protected def checkTypesInternal(t: DataType) = TypeCheckResult.TypeCheckSuccess

  protected override def nullSafeEval(input1: Any, input2: Any): Any = {
    if (left.dataType != BinaryType) input1 == input2
    else java.util.Arrays.equals(input1.asInstanceOf[Array[Byte]], input2.asInstanceOf[Array[Byte]])
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    defineCodeGen(ctx, ev, (c1, c2) => ctx.genEqual(left.dataType, c1, c2))
  }
}

case class EqualNullSafe(left: Expression, right: Expression) extends BinaryComparison {
  override def symbol: String = "<=>"

  override def nullable: Boolean = false

  override protected def checkTypesInternal(t: DataType) = TypeCheckResult.TypeCheckSuccess

  override def eval(input: InternalRow): Any = {
    val input1 = left.eval(input)
    val input2 = right.eval(input)
    if (input1 == null && input2 == null) {
      true
    } else if (input1 == null || input2 == null) {
      false
    } else {
      if (left.dataType != BinaryType) {
        input1 == input2
      } else {
        java.util.Arrays.equals(input1.asInstanceOf[Array[Byte]], input2.asInstanceOf[Array[Byte]])
      }
    }
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val eval1 = left.gen(ctx)
    val eval2 = right.gen(ctx)
    val equalCode = ctx.genEqual(left.dataType, eval1.primitive, eval2.primitive)
    ev.isNull = "false"
    eval1.code + eval2.code + s"""
        boolean ${ev.primitive} = (${eval1.isNull} && ${eval2.isNull}) ||
           (!${eval1.isNull} && $equalCode);
      """
  }
}

case class LessThan(left: Expression, right: Expression) extends BinaryComparison {
  override def symbol: String = "<"

  override protected def checkTypesInternal(t: DataType) =
    TypeUtils.checkForOrderingExpr(left.dataType, "operator " + symbol)

  private lazy val ordering = TypeUtils.getOrdering(left.dataType)

  protected override def nullSafeEval(input1: Any, input2: Any): Any = ordering.lt(input1, input2)
}

case class LessThanOrEqual(left: Expression, right: Expression) extends BinaryComparison {
  override def symbol: String = "<="

  override protected def checkTypesInternal(t: DataType) =
    TypeUtils.checkForOrderingExpr(left.dataType, "operator " + symbol)

  private lazy val ordering = TypeUtils.getOrdering(left.dataType)

  protected override def nullSafeEval(input1: Any, input2: Any): Any = ordering.lteq(input1, input2)
}

case class GreaterThan(left: Expression, right: Expression) extends BinaryComparison {
  override def symbol: String = ">"

  override protected def checkTypesInternal(t: DataType) =
    TypeUtils.checkForOrderingExpr(left.dataType, "operator " + symbol)

  private lazy val ordering = TypeUtils.getOrdering(left.dataType)

  protected override def nullSafeEval(input1: Any, input2: Any): Any = ordering.gt(input1, input2)
}

case class GreaterThanOrEqual(left: Expression, right: Expression) extends BinaryComparison {
  override def symbol: String = ">="

  override protected def checkTypesInternal(t: DataType) =
    TypeUtils.checkForOrderingExpr(left.dataType, "operator " + symbol)

  private lazy val ordering = TypeUtils.getOrdering(left.dataType)

  protected override def nullSafeEval(input1: Any, input2: Any): Any = ordering.gteq(input1, input2)
}
