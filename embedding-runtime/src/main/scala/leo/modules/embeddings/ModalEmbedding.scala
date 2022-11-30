package leo
package modules
package embeddings

import datastructures.{FlexMap, TPTP}
import TPTP.{AnnotatedFormula, THF, THFAnnotated}

import scala.annotation.unused
import scala.collection.mutable

object ModalEmbedding extends Embedding {
  object ModalEmbeddingOption extends Enumeration {
    // Hidden on purpose, to allow distinction between the object itself and its values.
    //    type ModalEmbeddingOption = Value
    @unused
    final val MONOMORPHIC, POLYMORPHIC,
    MODALITIES_SEMANTICAL, MODALITIES_SYNTACTICAL,
    DOMAINS_SEMANTICAL, DOMAINS_SYNTACTICAL = Value
  }

  override type OptionType = ModalEmbeddingOption.type
  override final def embeddingParameter: ModalEmbeddingOption.type = ModalEmbeddingOption

  override final def name: String = "modal"
  override final def version: String = "1.5.5"

  private[this] final val defaultConstantSpec = "$rigid"
  private[this] final val defaultQuantificationSpec = "$constant"
  private[this] final val defaultModalitiesSpec = "$modal_system_K"
  override final def generateSpecification(specs: Map[String, String]): TPTP.THFAnnotated = {
    import modules.input.TPTPParser.annotatedTHF
    val spec: mutable.StringBuilder = new mutable.StringBuilder
    spec.append("thf(logic_spec, logic, (")
    spec.append("$modal == [")
    spec.append("$constants == "); spec.append(specs.getOrElse("$constants", defaultConstantSpec)); spec.append(",")
    spec.append("$quantification == "); spec.append(specs.getOrElse("$quantification", defaultQuantificationSpec)); spec.append(",")
    spec.append("$modalities == "); spec.append(specs.getOrElse("$modalities", defaultModalitiesSpec))
    spec.append("] )).")
    annotatedTHF(spec.toString)
  }

  override final def apply(problem: TPTP.Problem,
                  embeddingOptions: Set[ModalEmbeddingOption.Value]): TPTP.Problem =
    new ModalEmbeddingImpl(problem, embeddingOptions).apply()

  override final def apply(formulas: Seq[TPTP.AnnotatedFormula],
                           embeddingOptions: Set[ModalEmbeddingOption.Value]): Seq[TPTP.AnnotatedFormula] =
    apply(TPTP.Problem(Seq.empty, formulas, Map.empty), embeddingOptions).formulas

  /////////////////////////////////////////////////////////////////////////////////
  /////////////////////////////////////////////////////////////////////////////////
  // The embedding
  /////////////////////////////////////////////////////////////////////////////////
  /////////////////////////////////////////////////////////////////////////////////
  private[this] final class ModalEmbeddingImpl(problem: TPTP.Problem, embeddingOptions: Set[ModalEmbeddingOption.Value]) {
    import ModalEmbeddingOption._
    import scala.collection.mutable
    ///////////////////////////////////////////////////////////////////
    private final val state = FlexMap.apply()

    // Semantics dimensions
    // (1) Rigid or flexible symbols
    private[this] sealed abstract class Rigidity
    private[this] final case object Rigid extends Rigidity
    private[this] final case object Flexible extends Rigidity
    private[this] var rigidityMap: Map[String, Rigidity] = Map.empty
    private[this] var reverseSymbolTypeMap: Map[THF.Type, Set[String]] = Map.empty.withDefaultValue(Set.empty)
    /* Initialize map */
    tptpDefinedPredicateSymbols.foreach { pred => rigidityMap += (pred -> Rigid) }
    tptpDefinedFunctionSymbols.foreach { pred => rigidityMap += (pred -> Rigid) }

    // (2) Quantification semantics
    private[this] sealed abstract class DomainType
    private[this] final case object ConstantDomain extends DomainType
    private[this] final case object VaryingDomain extends DomainType
    private[this] final case object CumulativeDomain extends DomainType
    private[this] final case object DecreasingDomain extends DomainType
    private[this] var domainMap: Map[String, DomainType] = Map.empty
    /* Initialize map */
    domainMap += ("$o" -> ConstantDomain)

    // (3) Modal operator properties, for now as string
    private final val MODALS = state.createKey[THF.Formula, Seq[String]]()
    ////////////////////////////////////////////////////////////////////
    // Embedding options
    private val polymorphic: Boolean = embeddingOptions.contains(POLYMORPHIC) // default monomorphic

    private final val MODALITY_EMBEDDING_SYNTACTICAL = true
    private final val MODALITY_EMBEDDING_SEMANTICAL = false
    private val modalityEmbeddingType: Boolean = embeddingOptions.contains(MODALITIES_SYNTACTICAL) // default semantical

    private final val DOMAINS_EMBEDDING_SYNTACTICAL = true
    private final val DOMAINS_EMBEDDING_SEMANTICAL = false
    private val domainEmbeddingType: Boolean = embeddingOptions.contains(DOMAINS_SYNTACTICAL) // default semantical
    ////////////////////////////////////////////////////////////////////

    def apply(): TPTP.Problem = {
      import leo.modules.tptputils.SyntaxTransform.transformProblem
      val problemTHF = transformProblem(TPTP.AnnotatedFormula.FormulaType.THF, problem, addMissingTypeDeclarations = true)
      val formulas = problemTHF.formulas

      val (spec,sortFormulas,typeFormulas,definitionFormulas,otherFormulas) = splitTHFInputByDifferentKindOfFormulas(formulas)
      createState(spec)
      val convertedTypeFormulas = typeFormulas.map(convertTypeFormula)
      val convertedDefinitionFormulas = definitionFormulas.map(convertDefinitionFormula)
      val convertedOtherFormulas = otherFormulas.map(convertAnnotatedFormula)
      val generatedMetaFormulas: Seq[AnnotatedFormula] = generateMetaFormulas()

      // new user types first (sort formulas), then our definitions, then all other formulas
      val result = sortFormulas ++ generatedMetaFormulas ++ convertedTypeFormulas ++ convertedDefinitionFormulas ++ convertedOtherFormulas
      // maybe add comments about warnings etc. in comments. If so, add them to very first formula in output.
      val updatedComments =
        if (result.isEmpty || warnings.isEmpty) problem.formulaComments
        else {
          val firstFormula = result.head
          val existingCommentsOfFirstFormula = problem.formulaComments.get(firstFormula.name)
          val newEntry = existingCommentsOfFirstFormula match {
            case Some(value) => warnings.toSeq.map(TPTP.Comment(TPTP.Comment.CommentFormat.LINE, TPTP.Comment.CommentType.NORMAL, _)) ++ value
            case None => warnings.toSeq.map(TPTP.Comment(TPTP.Comment.CommentFormat.LINE, TPTP.Comment.CommentType.NORMAL, _))
          }
          problem.formulaComments + (firstFormula.name -> newEntry)
        }

      TPTP.Problem(problem.includes, result, updatedComments)
    }

    def convertDefinitionFormula(formula: THFAnnotated): AnnotatedFormula = {
      formula match {
        case THFAnnotated(name, "definition", THF.Logical(THF.BinaryFormula(THF.Eq, THF.FunctionTerm(symbolName, Seq()), body)), annotations) =>
          THFAnnotated(name, "definition", THF.Logical(THF.BinaryFormula(THF.Eq, THF.FunctionTerm(symbolName, Seq()), convertFormula(body))), annotations)
        case _ => convertAnnotatedFormula(formula)
      }
    }

    def convertAnnotatedFormula(formula: THFAnnotated): AnnotatedFormula = {
      import leo.modules.tptputils._
      formula match {
        case TPTP.THFAnnotated(name, role, TPTP.THF.Logical(formula), annotations) =>
          val convertedFormula0 = convertFormula(formula)
          val convertedFormula = role match {
            case "hypothesis" | "conjecture" => // assumed to be local
              localFormulaExists = true
              THF.BinaryFormula(THF.App, mlocal, convertedFormula0)
            case _ if isSimpleRole(role) => // everything else is assumed to be global
              globalFormulaExists = true
              THF.BinaryFormula(THF.App, mglobal, convertedFormula0)
            case _ => // role with subroles, check whether a subrole specified $local or $global explicitly
              getSubrole(role).get match {
                case "local" =>
                  localFormulaExists = true
                  THF.BinaryFormula(THF.App, mlocal, convertedFormula0)
                case "global" =>
                  globalFormulaExists = true
                  THF.BinaryFormula(THF.App, mglobal, convertedFormula0)
                case x => throw new EmbeddingException(s"Unknown subrole '$x' in conversion of formula '$name'. ")
              }
          }
          // Strip $local, $global etc. role contents from role (as classical ATPs cannot deal with it)
          // And normalize hypothesis to axiom.
          val updatedRole = toSimpleRole(role) match {
            case "hypothesis" => "axiom"
            case r => r
          }
          TPTP.THFAnnotated(name, updatedRole, TPTP.THF.Logical(convertedFormula), annotations)
        case _ => throw new EmbeddingException(s"Only embedding of THF files supported.")
      }
    }
//
//    private def mkLambda(variable: THF.TypedVariable, body: THF.Formula): THF.Formula = {
//      THF.QuantifiedFormula(THF.^, Seq(variable), body)
//    }
//
    private def mkSingleQuantified(quantifier: THF.Quantifier, worldName: String)(variable: THF.TypedVariable, acc: THF.Formula): THF.Formula = {
      val convertedType = convertVariableType(variable._2)
      val convertedVariable: THF.TypedVariable = (variable._1, convertedType)
      quantifierType(convertedType)
      if (domainMap(variable._2.pretty) == ConstantDomain) THF.QuantifiedFormula(quantifier, Seq(convertedVariable), acc)
      else { /* with exists-in-world guard */
        val eiwPredicate = if (polymorphic) "eiw" else s"eiw_${serializeType(convertedType)}"
        val appliedEiw = THF.BinaryFormula(THF.App, THF.BinaryFormula(THF.App, str2Fun(eiwPredicate), THF.Variable(variable._1)), THF.Variable(worldName))
        val convertedBody = quantifier match {
          case THF.! => THF.BinaryFormula(THF.Impl, appliedEiw, acc)
          case THF.? => THF.BinaryFormula(THF.&, appliedEiw, acc)
          case _ => acc
        }
        THF.QuantifiedFormula(quantifier, Seq(convertedVariable), convertedBody)
      }
//  val convertedQuantifier: THF.Formula =
//        if (polymorphic) THF.BinaryFormula(THF.App, convertQuantifier(quantifier, variable._2, convertedVariable._2), convertedVariable._2)
//        else convertQuantifier(quantifier, variable._2, convertedVariable._2)
//      THF.BinaryFormula(THF.App, convertedQuantifier, mkLambda(convertedVariable, acc))
    }

    private def getRigidityOfSymbol(name: String, typ: THF.Type): Rigidity = {
      rigidityMap.get(name) match {
        case Some(value) => value
        case None =>
          val goal: TPTP.THF.Type = goalType(typ)
          goal match { // Special treatment for formulas/predicates: flexible by default
            case THF.FunctionTerm("$o", Seq()) => Flexible
            case _ => rigidityMap(name) // use default
          }
      }
    }

    private def getDefaultRigidityOfType(typ: THF.Type): Rigidity = {
      val goal: TPTP.THF.Type = goalType(typ)
      goal match { // Special treatment for formulas/predicates variables: flexible
        case THF.FunctionTerm("$o", Seq()) => Flexible
        case _ => Rigid // other variables are rigid.
      }
    }

    private[this] def generateSafeName(boundVars: Map[String, THF.Type]): String = {
      var proposedName: String = "W"
      while (boundVars.contains(proposedName)) {
        proposedName = proposedName.concat("W")
      }
      proposedName
    }
    @inline private[this] def worldAbstraction(body: THF.Formula, boundVars: Map[String, THF.Type]): THF.Formula =
      THF.QuantifiedFormula(THF.^, Seq((generateSafeName(boundVars),THF.FunctionTerm(worldTypeName, Seq.empty))), body)

    @inline private[this] def worldAbstraction(body: THF.Formula, name: String): THF.Formula =
      THF.QuantifiedFormula(THF.^, Seq((name, THF.FunctionTerm(worldTypeName, Seq.empty))), body)

    @inline private def convertFormula(formula: THF.Formula): THF.Formula = convertFormula(formula, Map.empty)
    private def convertFormula(formula: THF.Formula, boundVars: Map[String, THF.Type]): THF.Formula = {
      import TPTP.THF.App

      formula match {
        case THF.FunctionTerm(f, args) =>
          val convertedArgs = THF.FunctionTerm(f, args.map(convertFormula(_, boundVars)))
          rigidityMap(f) match {
            case Rigid => worldAbstraction(convertedArgs, boundVars)
            case Flexible => convertedArgs
          }

          // Special case: Modal operators
        case THF.BinaryFormula(App, THF.ConnectiveTerm(nclConnective), body) if nclConnective.isInstanceOf[THF.VararyConnective] =>
          val convertedBody = convertFormula(body, boundVars)
          val convertedConnective: THF.Formula = nclConnective match {
            case THF.NonclassicalBox(index) => index match {
              case Some(index0) => mboxIndexed(index0)
              case None => str2Fun("mbox")
            }
            case THF.NonclassicalDiamond(index) => index match {
              case Some(index0) => mdiaIndexed(index0)
              case None => str2Fun("mdia")
            }
            case THF.NonclassicalLongOperator(name, parameters) =>
              name match {
                case "$box" | "$necessary" | "$obligatory" | "$knows" | "$believes" => parameters match {
                  case Seq() => str2Fun("mbox")
                  case Seq(Left(index0)) => mboxIndexed(index0)
                  case _ => throw new EmbeddingException(s"Only up to one index is allowed in box operator, but parameters '${parameters.toString()}' was given.")
                }
                case "$dia" | "$possible" | "$permissible" | "$canKnow" | "$canBelieve" => parameters match {
                  case Seq() => str2Fun("mdia")
                  case Seq(Left(index0)) => mdiaIndexed(index0)
                  case _ => throw new EmbeddingException(s"Only up to one index is allowed in diamond operator, but parameters '${parameters.toString()}' was given.")
                }
                case "$forbidden" => parameters match {
                  case Seq() =>
                    val box = str2Fun("mbox")
                    modules.input.TPTPParser.thf(s"^[Phi: $worldTypeName > $$o]: (${box.pretty} @ (mnot @ Phi))")
                  case Seq(Left(index0)) =>
                    val box = mboxIndexed(index0)
                    modules.input.TPTPParser.thf(s"^[Phi: $worldTypeName > $$o]: (${box.pretty} @ (mnot @ Phi))")
                  case _ => throw new EmbeddingException(s"Only up to one index is allowed in box operator, but parameters '${parameters.toString()}' was given.")
                }
                case _ => throw new EmbeddingException(s"Unknown connective name '$name'.")
              }
            case _ => throw new EmbeddingException(s"Unsupported non-classical operator: '${nclConnective.pretty}'")
          }
          THF.BinaryFormula(THF.App, convertedConnective, convertedBody)

        case THF.BinaryFormula(connective, left, right) =>
          val convertedLeft: TPTP.THF.Formula = convertFormula(left, boundVars)
          val convertedRight: TPTP.THF.Formula = convertFormula(right, boundVars)
          val safeName = generateSafeName(boundVars) // a name that is not free in the formula
          val appliedLeft = THF.BinaryFormula(THF.App, convertedLeft, THF.Variable(safeName))
          val appliedRight = THF.BinaryFormula(THF.App, convertedRight, THF.Variable(safeName))
          worldAbstraction(THF.BinaryFormula(connective, appliedLeft, appliedRight), safeName)
//          thf(s"^[W: $worldTypeName]: ((${convertedLeft.pretty} @ W) @ (${convertedRight.pretty} @ W))")

//        case THF.QuantifiedFormula(THF.^, variableList, body) =>
//          val convertedBody = convertFormula(body)
////          val convertedVariableList = variableList.map {case (name, ty) => (name, convertConstantSymbolType(ty))}
////          THF.QuantifiedFormula(THF.^, convertedVariableList, convertedBody)
//        thf(s"^[W: $worldTypeName]: (^[${variableList.map(vari => s"${vari._1}:${vari._2.pretty}").mkString(",")}]: (${convertedBody.pretty}))")

        case THF.QuantifiedFormula(quantifier, variableList, body) =>
          /* in theory, this suffices: (a quantified expression is just a lambda applied to constant) */
          //          val rewritten = THF.BinaryFormula(App, <quantifier-as-constant>, <body as lambda abstraction>)
          //          convertFormula(rewritten, boundVars) -- with a lambda base case
          /* But this results in a ugly formula, because TPTP has a dedicated syntax for quantifications */
          /* So we replace the result of doing this with an indentical expression uses a nicer syntax */
//          val updatedVariableList = variableList.map {case (name, ty) => (name, convertVariableType(ty)) }
          val updatedBoundVars = boundVars.concat(variableList)
          val convertedBody = convertFormula(body, updatedBoundVars)
          val safeName = generateSafeName(updatedBoundVars) // a name that is not free in the formula
          val appliedBody: THF.Formula = THF.BinaryFormula(App, convertedBody, THF.Variable(safeName))
          // Step through variables one-by-one to allow introducing exists-in-world-predicates
          val result = variableList.foldRight(appliedBody)(mkSingleQuantified(quantifier, safeName))

//          variableList.foldRight(convertedBody)(mkSingleQuantified(quantifier))
          worldAbstraction(result, safeName)
//          thf(s"^[W: $worldTypeName]: (${quantifier.pretty} [${variableList.map(vari => s"${vari._1}:${vari._2.pretty}").mkString(",")}]: ((${convertedBody.pretty}) @ W))")

        case THF.Variable(name) =>
          boundVars.get(name) match {
            case Some(ty) =>
              val goal = goalType(ty)
              goal match { // Special treatment for formulas/predicates: flexible
                case THF.FunctionTerm("$o", Seq()) => formula
                case _ => worldAbstraction(formula, boundVars) //make type correct by abstraction
              }
            case None => worldAbstraction(formula, boundVars) //loose bound variable, just do anything; the formula is not well-formed anyway.
          }

        case THF.UnaryFormula(connective, body) =>
          /* in theory, this suffices: (a connective is just a constant applied to arguments) */
//          val convertedConnective: THF.Formula = convertFormula(THF.ConnectiveTerm(connective), boundVars)
//          val rewritten = THF.BinaryFormula(App, convertedConnective, body)
//          convertFormula(rewritten, boundVars)
          /* But this results in a ugly formula, because TPTP has a dedicated syntax for connectives */
          /* So we replace the result of doing this with an indentical expression uses a nicer syntax */
          val convertedBody: TPTP.THF.Formula = convertFormula(body, boundVars)
          val safeName = generateSafeName(boundVars) // a name that is not free in the formula
          val appliedBody = THF.BinaryFormula(THF.App, convertedBody, THF.Variable(safeName))
          worldAbstraction(THF.UnaryFormula(connective, appliedBody), safeName)

//          val convertedConnective: TPTP.THF.Formula = convertConnective(connective)
//          val convertedBody: TPTP.THF.Formula = convertFormula(body, boundVars)
//          THF.BinaryFormula(App, convertedConnective, convertedBody)

        /* = and != are extra cases so we don't need to introduce defined symbols for them. Only works for first-order equality. */
//        case THF.BinaryFormula(equalityLike, left, right) if Seq(Eq, Neq).contains(equalityLike) =>
//          warnings.append(" WARNING: Equality is interpreted as full congruence, also under modal operators. This might " +
//            "not be want you want (or it may). Proceed with care, you have been warned.")
//          val convertedLeft: TPTP.THF.Formula = convertFormula(left, boundVars)
//          val convertedRight: TPTP.THF.Formula = convertFormula(right, boundVars)
//          val body = THF.BinaryFormula(equalityLike, convertedLeft, convertedRight)
//          worldAbstraction(body, boundVars)
//          THF.QuantifiedFormula(THF.^, Seq(("W", THF.FunctionTerm(worldTypeName, Seq.empty))), body)

        /* connective is always a classical connective, because the {...} @ things are ConnectiveTerms applied to arguments. */
//        case THF.BinaryFormula(connective, left, right) =>
          /* in theory, this suffices: (a connective is just a constant applied to arguments) */
//          val convertedConnective: THF.Formula = convertFormula(THF.ConnectiveTerm(connective), boundVars)
//          val rewritten = THF.BinaryFormula(App, THF.BinaryFormula(App, convertedConnective, left), right)
//          convertFormula(rewritten, boundVars)
          /* But this results in a ugly formula, because TPTP has a dedicated syntax for connectives */
          /* So we replace the result of doing this with an indentical expression uses a nicer syntax */



//          val convertedConnective: TPTP.THF.Formula = convertConnective(connective)
//          val convertedLeft: TPTP.THF.Formula = convertFormula(left, boundVars)
//          val convertedRight: TPTP.THF.Formula = convertFormula(right, boundVars)
//          THF.BinaryFormula(App, THF.BinaryFormula(App, convertedConnective, convertedLeft), convertedRight)
//          val appliedLeft = THF.BinaryFormula(App, convertedLeft, THF.Variable("W"))
//          val appliedRight = THF.BinaryFormula(App, convertedRight, THF.Variable("W"))
//          val combined = THF.BinaryFormula(connective, appliedLeft, appliedRight)
//          THF.QuantifiedFormula(THF.^, Seq(("W", THF.FunctionTerm(worldTypeName, Seq.empty))), combined)
//          THF.BinaryFormula(App, THF.BinaryFormula(App, convertedConnective, convertedLeft), convertedRight)

        case THF.ConnectiveTerm(conn) =>
          val converted = conn match {
            case _: THF.VararyConnective => throw new EmbeddingException("Modal operators not allowed as constant symbols.")
//            // Box operator
//            case THF.NonclassicalBox(index) => index match {
//              case Some(index0) => mboxIndexed(index0)
//              case None => str2Fun("mbox")
//            }
//            // Diamond operator
//            case THF.NonclassicalDiamond(index) => index match {
//              case Some(index0) => mdiaIndexed(index0)
//              case None => str2Fun("mdia")
//            }
//            case THF.NonclassicalLongOperator(name, parameters) =>
//              name match {
//                case "$box" | "$necessary" | "$obligatory" | "$knows" | "$believes" => parameters match {
//                  case Seq() => str2Fun("mbox")
//                  case Seq(Left(index0)) => mboxIndexed(index0)
//                  case _ => throw new EmbeddingException(s"Only up to one index is allowed in box operator, but parameters '${parameters.toString()}' was given.")
//                }
//                case "$dia" | "$possible" | "$permissible" | "$canKnow" | "$canBelieve" => parameters match {
//                  case Seq() => str2Fun("mdia")
//                  case Seq(Left(index0)) => mdiaIndexed(index0)
//                  case _ => throw new EmbeddingException(s"Only up to one index is allowed in diamond operator, but parameters '${parameters.toString()}' was given.")
//                }
//                case "$forbidden" => parameters match {
//                  case Seq() =>
//                    val box = str2Fun("mbox")
//                    modules.input.TPTPParser.thf(s"^[Phi: $worldTypeName > $$o]: (${box.pretty} @ (mnot @ Phi))")
//                  case Seq(Left(index0)) =>
//                    val box = mboxIndexed(index0)
//                    modules.input.TPTPParser.thf(s"^[Phi: $worldTypeName > $$o]: (${box.pretty} @ (mnot @ Phi))")
//                  case _ => throw new EmbeddingException(s"Only up to one index is allowed in box operator, but parameters '${parameters.toString()}' was given.")
//                }
//                case _ => throw new EmbeddingException(s"Unknown connective name '$name'.")
//              }
            case _ => formula
          }
          worldAbstraction(converted, boundVars) // connectives are rigid: so do a world abstraction
//          THF.QuantifiedFormula(THF.^, Seq(("W", THF.FunctionTerm(worldTypeName, Seq.empty))), converted)


        case THF.DefinedTH1ConstantTerm(_) =>
          worldAbstraction(formula, boundVars)
//          THF.QuantifiedFormula(THF.^, Seq(("W", THF.FunctionTerm(worldTypeName, Seq.empty))), formula)

        case THF.Tuple(elements) =>
          val convertedElements: Seq[TPTP.THF.Formula] = elements.map(convertFormula(_, boundVars))
          THF.Tuple(convertedElements)

        case THF.ConditionalTerm(condition, thn, els) =>
          val convertedCondition: TPTP.THF.Formula = convertFormula(condition, boundVars)
          val convertedThn: TPTP.THF.Formula = convertFormula(thn, boundVars)
          val convertedEls: TPTP.THF.Formula = convertFormula(els, boundVars)
          val safeName = generateSafeName(boundVars) // a name that is not free in the formula
          val appliedCond = THF.BinaryFormula(THF.App, convertedCondition, THF.Variable(safeName))
          val appliedThn = THF.BinaryFormula(THF.App, convertedThn, THF.Variable(safeName))
          val appliedEls = THF.BinaryFormula(THF.App, convertedEls, THF.Variable(safeName))
          worldAbstraction(THF.ConditionalTerm(appliedCond, appliedThn, appliedEls), safeName)
//          localFormulaExists = true  // For what was this here?
//          val conditionalBody = THF.ConditionalTerm(
//            THF.BinaryFormula(THF.App, convertedCondition, THF.Variable("W")),
//            THF.BinaryFormula(THF.App, convertedThn, THF.Variable("W")),
//            THF.BinaryFormula(THF.App, convertedEls, THF.Variable("W")))
//          THF.QuantifiedFormula(THF.^, Seq(("W", THF.FunctionTerm(worldTypeName, Seq.empty))), conditionalBody)


        case THF.LetTerm(typing, binding, body) => // Treat new symbols like bound variables
          val convertedTyping: Map[String, TPTP.THF.Type] = typing.map { case (name, ty) => (name, convertVariableType(ty)) }
          val convertedBinding: Seq[(TPTP.THF.Formula, TPTP.THF.Formula)] = binding.map(a => (convertFormula(a._1, boundVars), convertFormula(a._2, boundVars)))
          val convertedBody = convertFormula(body, boundVars)
          THF.LetTerm(convertedTyping, convertedBinding, convertedBody)
        case THF.DistinctObject(_) => worldAbstraction(formula, boundVars)
        case THF.NumberTerm(_) => worldAbstraction(formula, boundVars)
      }
    }

//    private[this] val inlineMifDef: THF.Formula =
//      modules.input.TPTPParser.thf(s"^[A: $worldTypeName > $$o, B: $worldTypeName > $$o]: (mimplies @ B @ A)")
//    private[this] val inlineMniffDef: THF.Formula =
//      modules.input.TPTPParser.thf(s"^[A: $worldTypeName > $$o, B: $worldTypeName > $$o]: (mnot @ (mequiv @ A @ B))")
//    private[this] val inlineMnorDef: THF.Formula =
//      modules.input.TPTPParser.thf(s"^[A: $worldTypeName > $$o, B: $worldTypeName > $$o]: (mnot @ (mor @ A @ B))")
//    private[this] val inlineMnandDef: THF.Formula =
//      modules.input.TPTPParser.thf(s"^[A: $worldTypeName > $$o, B: $worldTypeName > $$o]: (mnot @ (mand @ A @ B))")

//    private[this] def convertConnective(connective: TPTP.THF.Connective): THF.Formula = {
//      connective match {
//        case THF.~ => str2Fun("mnot")
//        case THF.<=> => str2Fun("mequiv")
//        case THF.Impl => str2Fun("mimplies")
//        case THF.| => str2Fun("mor")
//        case THF.& => str2Fun("mand")
//        // other connectives of TPTP are encoded in terms of the above
//        case THF.<= => inlineMifDef
//        case THF.<~> => inlineMniffDef
//        case THF.~| => inlineMnorDef
//        case THF.~& => inlineMnandDef
//        /// Non-classical connectives BEGIN
//        // Box operator
//        case THF.NonclassicalBox(index) => index match {
//          case Some(index0) => mboxIndexed(index0)
//          case None => str2Fun("mbox")
//        }
//        // Diamond operator
//        case THF.NonclassicalDiamond(index) => index match {
//          case Some(index0) => mdiaIndexed(index0)
//          case None => str2Fun("mdia")
//        }
//        case THF.NonclassicalLongOperator(name, parameters) =>
//          name match {
//            case "$box" | "$necessary" | "$obligatory" | "$knows" | "$believes" => parameters match {
//              case Seq() => str2Fun("mbox")
//              case Seq(Left(index0)) => mboxIndexed(index0)
//              case _ => throw new EmbeddingException(s"Only up to one index is allowed in box operator, but parameters '${parameters.toString()}' was given.")
//            }
//            case "$dia" | "$possible" | "$permissible" | "$canKnow" | "$canBelieve" => parameters match {
//              case Seq() => str2Fun("mdia")
//              case Seq(Left(index0)) => mdiaIndexed(index0)
//              case _ => throw new EmbeddingException(s"Only up to one index is allowed in diamond operator, but parameters '${parameters.toString()}' was given.")
//            }
//            case "$forbidden" => parameters match {
//              case Seq() =>
//                val box = str2Fun("mbox")
//                modules.input.TPTPParser.thf(s"^[Phi: $worldTypeName > $$o]: (${box.pretty} @ (mnot @ Phi))")
//              case Seq(Left(index0)) =>
//                val box = mboxIndexed(index0)
//                modules.input.TPTPParser.thf(s"^[Phi: $worldTypeName > $$o]: (${box.pretty} @ (mnot @ Phi))")
//              case _ => throw new EmbeddingException(s"Only up to one index is allowed in box operator, but parameters '${parameters.toString()}' was given.")
//            }
//            case _ => throw new EmbeddingException(s"Unknown connective name '$name'.")
//          }
//
//        /// Non-classical connectives END
//        // Error cases
//        case THF.App | THF.Eq | THF.Neq => throw new EmbeddingException(s"An unexpected error occurred, this is considered a bug. Please report it :-)")
//        case THF.:= => throw new EmbeddingException(s"Unexpected assignment operator used as connective.")
//        case THF.== => throw new EmbeddingException(s"Unexpected meta-logical identity operator used as connective.")
//        case _ => throw new EmbeddingException(s"Unexpected type constructor used as connective: '${connective.pretty}'")
//      }
//    }

//    private def convertQuantifier(quantifier: TPTP.THF.Quantifier, typ: TPTP.THF.Type, convertedType: TPTP.THF.Type): THF.Formula = {
//
//      val name = quantifier match {
//        case THF.! =>
//          try {
//            domainMap(typ.pretty) match {
//              case ConstantDomain => if (polymorphic) "mforall_const" else s"mforall_${serializeType(convertedType)}"
//              case _ => // all three other cases
//                if (polymorphic) "mforall_vary" else s"mforall_${serializeType(convertedType)}"
//            }
//          } catch {
//            case _: NoSuchElementException => throw new EmbeddingException(s"Undefined domain semantics for type '${typ.pretty}'. Maybe a default value was omitted?")
//          }
//
//        case THF.? =>
//          try {
//            domainMap(typ.pretty) match {
//              case ConstantDomain => if (polymorphic) "mexists_const" else s"mexists_${serializeType(convertedType)}"
//              case _ => // all three other cases
//                if (polymorphic) "mexists_vary" else s"mexists_${serializeType(convertedType)}"
//            }
//          } catch {
//            case _: NoSuchElementException => throw new EmbeddingException(s"Undefined domain semantics for type '${typ.pretty}'. Maybe a default value was omitted?")
//          }
//        case THF.@+ => "mchoice"
//        case THF.@- => "mdescription"
//        case _ => throw new EmbeddingException(s"Unexpected quantifier used as term quantifier: '${quantifier.pretty}'")
//      }
//      THF.FunctionTerm(name, Seq.empty)
//    }

    @inline private[this] def mbox: THF.Formula = str2Fun("mbox")
    private[this] def mboxIndexed(index: THF.Formula): THF.Formula = {
      THF.BinaryFormula(THF.App, mbox, multiModal(index))
    }
    @inline private[this] def mdia: THF.Formula = str2Fun("mdia")
    private[this] def mdiaIndexed(index: THF.Formula): THF.Formula = {
      THF.BinaryFormula(THF.App, mdia, multiModal(index))
    }

    @inline private[this] def mglobal: THF.Formula = str2Fun("mglobal")
    @inline private[this] def mlocal: THF.Formula =  str2Fun("mlocal")

    private def convertTypeFormula(formula: TPTP.THFAnnotated): AnnotatedFormula = {
      formula.formula match {
        case THF.Typing(symbol, typ) =>
          val rigidity = getRigidityOfSymbol(symbol, typ)
          rigidityMap = rigidityMap + (symbol -> rigidity) // add to table in case it was implicit (e.g. a predicate)
          val convertedType = convertConstantSymbolType(symbol,typ)
          reverseSymbolTypeMap = reverseSymbolTypeMap + (convertedType -> (reverseSymbolTypeMap(convertedType) + symbol))
          val convertedTyping = TPTP.THF.Typing(symbol, convertedType)
          TPTP.THFAnnotated(formula.name, formula.role, convertedTyping, formula.annotations)
        case _ => throw new EmbeddingException(s"Malformed type definition in formula '${formula.name}', aborting.")
      }
    }

    private def convertConstantSymbolType(symbol: String, typ: TPTP.THF.Type): TPTP.THF.Type = {
      val rigidity = getRigidityOfSymbol(symbol, typ)
      worldTypeLiftBasedOnRigidity(typ, rigidity)
    }

    private def convertVariableType(typ: TPTP.THF.Type): TPTP.THF.Type = {
      val rigidity = getDefaultRigidityOfType(typ)
      worldTypeLiftBasedOnRigidity(typ, rigidity)
    }
    @inline private[this] def worldTypeLiftBasedOnRigidity(typ: THF.Type, rigidity: Rigidity): THF.Type =
      rigidity match {
        case Rigid => typ
        case Flexible => THF.BinaryFormula(THF.FunTyConstructor, THF.FunctionTerm(worldTypeName, Seq.empty), typ)
      }

    ///////////////////////////////////////////////////
    // Local embedding state
    ///////////////////////////////////////////////////
    // For warnings that should go inside the output file
    private[this] val warnings: mutable.Buffer[String] = mutable.Buffer.empty

    private[this] var localFormulaExists = false
    private[this] var globalFormulaExists = false

    private[this] val modalOperators: mutable.Set[THF.FunctionTerm] = mutable.Set.empty
    private[this] def isMultiModal: Boolean = modalOperators.nonEmpty
    private[this] def multiModal(index: THF.Formula): THF.FunctionTerm = {
      val index0 = escapeModalIndex(index)
      modalOperators += index0
      index0
    }
    private[this] def escapeModalIndex(index: THF.Formula): THF.FunctionTerm = index match {
        case THF.FunctionTerm(name, args) => THF.FunctionTerm(s"#$name", args)
        case THF.NumberTerm(TPTP.Integer(value)) => THF.FunctionTerm(s"#$value", Seq.empty)
        case _ => throw new EmbeddingException(s"Unsupported index '${index.pretty}'")
      }

    private[this] val quantifierTypes: mutable.Set[THF.Type] = mutable.Set.empty
    private[this] def quantifierType(typ: THF.Type): Unit = {
      quantifierTypes += typ
    }

    private def generateMetaFormulas(): Seq[TPTP.AnnotatedFormula] = {
      import scala.collection.mutable

      val result: mutable.Buffer[TPTP.AnnotatedFormula] = mutable.Buffer.empty
      /////////////////////////////////////////////////////////////
      // First: Introduce world type
      result.append(worldTypeTPTPDef())
      // Then: Introduce index type (if multi-modal)
      if (isMultiModal) {
        result.append(indexTypeTPTPDef())
      }
      /////////////////////////////////////////////////////////////
      // Then: Introduce mrel (as relation or as collection of relations)
      if (isMultiModal) result.append(indexedAccessibilityRelationTPTPDef())
      else result.append(simpleAccessibilityRelationTPTPDef())
      /////////////////////////////////////////////////////////////
      // Then: Introduce index values (if multimodal)
      if (isMultiModal) {
        modalOperators.foreach { index =>
          result.append(indexTPTPDef(index))
        }
      }
      /////////////////////////////////////////////////////////////
      // Then: Define mglobal/mlocal
      if (localFormulaExists) {
        result.appendAll(mlocalTPTPDef())
      }
      if (globalFormulaExists ||
        modalityEmbeddingType == MODALITY_EMBEDDING_SYNTACTICAL ||  // We use mglobal for those meta-definitions
        domainEmbeddingType == DOMAINS_EMBEDDING_SYNTACTICAL) {     // so introduce mglobal also if there is no global
        result.appendAll(mglobalTPTPDef())                          // object-level formula
      }
      /////////////////////////////////////////////////////////////
      // Then: Define connectives
//      result.appendAll(connectivesTPTPDef())
      /////////////////////////////////////////////////////////////
      // Then: Define modal operators
      if (isMultiModal) result.appendAll(indexedModalOperatorsTPTPDef())
      else result.appendAll(simpleModalOperatorsTPTPDef())
      /////////////////////////////////////////////////////////////
      // Then: Give mrel properties (sem/syn)
      // write used properties and assign (if semantical)
      // or write syntactical axioms (if syntactical)
      if (isMultiModal) {
        val axiomTable = if (modalityEmbeddingType == MODALITY_EMBEDDING_SEMANTICAL) indexedSemanticAxiomTable else indexedSyntacticAxiomTable
        modalOperators foreach { index =>
          val modalSystem = state(MODALS).apply(index)
          val axiomNames = if (isModalSystemName(modalSystem.head)) modalSystemTable(modalSystem.head) else modalSystem
          axiomNames foreach { ax =>
            val axiom = axiomTable.apply(ax)
            axiom.foreach { f =>
              val res = f(index)
              result.append(res)
            }
          }
        }
      } else {
        val modalSystem = state.getDefault(MODALS)
        modalSystem match {
          case Some(value) =>
            val axiomNames = if (isModalSystemName(value.head)) modalSystemTable(value.head) else value
            val axiomTable = if (modalityEmbeddingType == MODALITY_EMBEDDING_SEMANTICAL) semanticAxiomTable else syntacticAxiomTable
            val modalAxioms = axiomNames.flatMap(axiomTable).toSet
            result.appendAll(modalAxioms)
          case None => throw new EmbeddingException(s"No semantics for modal operators specified, embedding not possible.")
        }
      }
      /////////////////////////////////////////////////////////////
      // Then: Define exist-in-world-predicates and quantifier restrictions (if cumul/decr/vary and semantic embedding)
      // In case of syntactical embedding, we need to have the quantifier symbols defined first.
      if (polymorphic) {
        if (quantifierTypes.nonEmpty) {
          if (quantifierTypes.exists(ty => domainMap(ty.pretty) != ConstantDomain)) {
            result.appendAll(polyIndexedExistsInWorldTPTPDef()) // define poly eiw
            quantifierTypes.foreach { ty => // postulate existence for each constant symbol of that type (if any)
              reverseSymbolTypeMap(ty).foreach { constant =>
                result.appendAll(polyIndexedConstantExistsInWorldTPTPDef(ty, constant))
              }
            }
          }
          if (domainEmbeddingType == DOMAINS_EMBEDDING_SEMANTICAL) {
            quantifierTypes foreach { ty =>
              if (domainMap(ty.pretty) == CumulativeDomain)
                result.appendAll(polyIndexedCumulativeExistsInWorldTPTPDef(ty)) // define cumul axioms for eiw with that type
              if (domainMap(ty.pretty) == DecreasingDomain)
                result.appendAll(polyIndexedDecreasingExistsInWorldTPTPDef(ty)) // define decreasing axioms for eiw with that type
            }
          }
        }
      } else {
        quantifierTypes foreach { ty =>
          if (domainMap(ty.pretty) != ConstantDomain) {
            result.appendAll(indexedExistsInWorldTPTPDef(ty)) // define eiw with standard axioms
            quantifierTypes.foreach { ty => // postulate existence for each constant symbol of that type (if any)
              reverseSymbolTypeMap(ty).foreach { constant =>
                result.appendAll(indexedConstantExistsInWorldTPTPDef(ty, constant))
              }
            }
          }
          if (domainEmbeddingType == DOMAINS_EMBEDDING_SEMANTICAL) {
            if (domainMap(ty.pretty) == CumulativeDomain)
              result.appendAll(indexedCumulativeExistsInWorldTPTPDef(ty)) // define cumul axioms for eiw
            else if (domainMap(ty.pretty) == DecreasingDomain)
              result.appendAll(indexedDecreasingExistsInWorldTPTPDef(ty)) // define decreasing axioms for eiw
          }
        }
      }
      /////////////////////////////////////////////////////////////
      // Then: Define quantifiers (TH0/TH1)
      if (polymorphic) {
        if (quantifierTypes.nonEmpty) {
//          if (quantifierTypes.exists(ty => domainMap(ty.pretty) == ConstantDomain))
//            result.appendAll(polyIndexedConstQuantifierTPTPDef())
//          if (quantifierTypes.exists(ty => domainMap(ty.pretty) != ConstantDomain))
//            result.appendAll(polyIndexedVaryQuantifierTPTPDef())
          if (domainEmbeddingType == DOMAINS_EMBEDDING_SYNTACTICAL) {
            // in case of syntactical embedding: write restrictions using CBF resp. BF now.
            quantifierTypes foreach { ty =>
              if (domainMap(ty.pretty) == CumulativeDomain) {
                result.appendAll(indexedConverseBarcanFormulaTPTPDef(ty))
              } else if (domainMap(ty.pretty) == DecreasingDomain) {
                result.appendAll(indexedBarcanFormulaTPTPDef(ty))
              }
            }
          }
        }
      } else {
        quantifierTypes foreach { ty =>
          if (domainMap(ty.pretty) == ConstantDomain) {} // result.appendAll(indexedConstQuantifierTPTPDef(ty))
          else {
//            result.appendAll(indexedVaryQuantifierTPTPDef(ty))
            if (domainEmbeddingType == DOMAINS_EMBEDDING_SYNTACTICAL) {
              // in case of syntactical embedding: write restrictions using CBF resp. BF now.
              if (domainMap(ty.pretty) == CumulativeDomain) {
                result.appendAll(indexedConverseBarcanFormulaTPTPDef(ty))
              } else if (domainMap(ty.pretty) == DecreasingDomain) {
                result.appendAll(indexedBarcanFormulaTPTPDef(ty))
              }
            }
          }
        }
      }
      /////////////////////////////////////////////////////////////
      // Return all
      result.toSeq
    }

    @inline private[this] def worldTypeName: String = "mworld"
    @inline private[this] def indexTypeName: String = "mindex"

    private[this] def worldTypeTPTPDef(): TPTP.AnnotatedFormula = {
      import modules.input.TPTPParser.annotatedTHF
      annotatedTHF(s"thf($worldTypeName, type, $worldTypeName: $$tType).")
    }
    private[this] def indexTypeTPTPDef(): TPTP.AnnotatedFormula = {
      import modules.input.TPTPParser.annotatedTHF
      annotatedTHF(s"thf($indexTypeName, type, $indexTypeName: $$tType).")
    }

    private[this] def indexTPTPDef(index: THF.FunctionTerm): TPTP.AnnotatedFormula = {
      import modules.input.TPTPParser.annotatedTHF
      val name = s"${unescapeTPTPName(index.pretty)}_type"
      annotatedTHF(s"thf(${escapeName(name)}, type, ${index.pretty}: $indexTypeName).")
    }

    private[this] def unescapeTPTPName(name: String): String = {
      if (name.startsWith("'") && name.endsWith("'")) {
        name.tail.init
      } else name
    }
    private def escapeName(name: String): String = {
      val integerRegex = "^[+-]?[\\d]+$"
      if (name.matches(integerRegex)) name else escapeAtomicWord(name)
    }
    private def escapeAtomicWord(word: String): String = {
      val simpleLowerWordRegex = "^[a-z][a-zA-Z\\d_]*$"
      if (word.matches(simpleLowerWordRegex)) word
      else s"'${word.replace("\\","\\\\").replace("'", "\\'")}'"
    }

    private[this] def simpleAccessibilityRelationTPTPDef(): TPTP.AnnotatedFormula = {
      import modules.input.TPTPParser.annotatedTHF
      annotatedTHF(s"thf(mrel_type, type, mrel: $worldTypeName > $worldTypeName > $$o).")
    }

    private[this] def indexedAccessibilityRelationTPTPDef(): TPTP.AnnotatedFormula = {
      import modules.input.TPTPParser.annotatedTHF
      annotatedTHF(s"thf(mrel_type, type, mrel: $indexTypeName > $worldTypeName > $worldTypeName > $$o).")
    }

    private[this] def mglobalTPTPDef(): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      Seq(
        annotatedTHF(s"thf(mglobal_type, type, mglobal: ($worldTypeName > $$o) > $$o)."),
        annotatedTHF(s"thf(mglobal_def, definition, mglobal = (^ [Phi: $worldTypeName > $$o]: ![W: $worldTypeName]: (Phi @ W)) ).")
      )
    }

    private[this] def mlocalTPTPDef(): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      Seq(
        annotatedTHF(s"thf(mactual_type, type, mactual: $worldTypeName)."),
        annotatedTHF(s"thf(mlocal_type, type, mlocal: ($worldTypeName > $$o) > $$o)."),
        annotatedTHF(s"thf(mlocal_def, definition, mlocal = (^ [Phi: $worldTypeName > $$o]: (Phi @ mactual)) ).")
      )
    }

    private[this] def connectivesTPTPDef(): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      Seq(
        annotatedTHF(s"thf(mnot_type, type , ( mnot: ($worldTypeName>$$o)>$worldTypeName>$$o) )."),
        annotatedTHF(s"thf(mand_type, type , ( mand: ($worldTypeName>$$o)>($worldTypeName>$$o)>$worldTypeName>$$o) )."),
        annotatedTHF(s"thf(mor_type, type , ( mor: ($worldTypeName>$$o)>($worldTypeName>$$o)>$worldTypeName>$$o) )."),
        annotatedTHF(s"thf(mimplies_type, type , ( mimplies: ($worldTypeName>$$o)>($worldTypeName>$$o)>$worldTypeName>$$o) )."),
        annotatedTHF(s"thf(mequiv_type, type , ( mequiv: ($worldTypeName>$$o)>($worldTypeName>$$o)>$worldTypeName>$$o) )."),
        annotatedTHF(s"thf(mnot_def, definition , ( mnot = (^ [A:$worldTypeName>$$o,W:$worldTypeName] : ~(A@W))))."),
        annotatedTHF(s"thf(mand_def, definition , ( mand = (^ [A:$worldTypeName>$$o,B:$worldTypeName>$$o,W:$worldTypeName] : ( (A@W) & (B@W) ))))."),
        annotatedTHF(s"thf(mor_def, definition , ( mor = (^ [A:$worldTypeName>$$o,B:$worldTypeName>$$o,W:$worldTypeName] : ( (A@W) | (B@W) ))))."),
        annotatedTHF(s"thf(mimplies_def, definition , ( mimplies = (^ [A:$worldTypeName>$$o,B:$worldTypeName>$$o,W:$worldTypeName] : ( (A@W) => (B@W) ))))."),
        annotatedTHF(s"thf(mequiv_def, definition , ( mequiv = (^ [A:$worldTypeName>$$o,B:$worldTypeName>$$o,W:$worldTypeName] : ( (A@W) <=> (B@W) )))).")
      )
    }

    private[this] def simpleModalOperatorsTPTPDef(): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      Seq(
        annotatedTHF(s"thf(mbox_type, type, mbox: ($worldTypeName>$$o)>$worldTypeName>$$o )."),
        annotatedTHF(s"thf(mbox_def, definition, ( mbox = (^ [Phi:$worldTypeName>$$o, W:$worldTypeName]: ![V:$worldTypeName]: ( (mrel @ W @ V) => (Phi @ V) ))))."),
        annotatedTHF(s"thf(mdia_type, type, mdia: ($worldTypeName>$$o)>$worldTypeName>$$o )."),
        annotatedTHF(s"thf(mdia_def, definition, ( mdia = (^ [Phi:$worldTypeName>$$o, W:$worldTypeName]: ?[V:$worldTypeName]: ( (mrel @ W @ V) & (Phi @ V) )))).")
      )
    }

    private[this] def indexedModalOperatorsTPTPDef(): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      Seq(
        annotatedTHF(s"thf(mbox_type, type, mbox: $indexTypeName > ($worldTypeName>$$o)>$worldTypeName>$$o )."),
        annotatedTHF(s"thf(mbox_def, definition, ( mbox = (^ [R:$indexTypeName, Phi:$worldTypeName>$$o,W:$worldTypeName]: ! [V:$worldTypeName]: ( (mrel @ R @ W @ V) => (Phi @ V) ))))."),
        annotatedTHF(s"thf(mdia_type, type, mdia: $indexTypeName > ($worldTypeName>$$o)>$worldTypeName>$$o )."),
        annotatedTHF(s"thf(mdia_def, definition, ( mdia = (^ [R:$indexTypeName, Phi:$worldTypeName>$$o, W:$worldTypeName]: ?[V:$worldTypeName]: ( (mrel @ R @ W @ V) & (Phi @ V) )))).")
      )
    }

    private[this] def indexedConstQuantifierTPTPDef(typ: THF.Type): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      Seq(
        annotatedTHF(s"thf(mforall_${serializeType(typ)}_type, type, mforall_${serializeType(typ)}: (${typ.pretty} > $worldTypeName > $$o) > $worldTypeName > $$o)."),
        annotatedTHF(s"thf(mforall_${serializeType(typ)}_def, definition, mforall_${serializeType(typ)} = ( ^ [A:${typ.pretty}>$worldTypeName>$$o, W:$worldTypeName]: ! [X:${typ.pretty}]: (A @ X @ W)))."),
        annotatedTHF(s"thf(mexists_${serializeType(typ)}_type, type, mexists_${serializeType(typ)}: (${typ.pretty} > $worldTypeName > $$o) > $worldTypeName > $$o)."),
        annotatedTHF(s"thf(mexists_${serializeType(typ)}_def, definition, mexists_${serializeType(typ)} = ( ^ [A:${typ.pretty}>$worldTypeName>$$o, W:$worldTypeName]: ? [X:${typ.pretty}]: (A @ X @ W))).")
      )
    }
    private[this] def indexedVaryQuantifierTPTPDef(typ: THF.Type): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      Seq(
        annotatedTHF(s"thf(mforall_${serializeType(typ)}_type, type, mforall_${serializeType(typ)}: (${typ.pretty} > $worldTypeName > $$o) > $worldTypeName > $$o)."),
        annotatedTHF(s"thf(mforall_${serializeType(typ)}_def, definition, mforall_${serializeType(typ)} = ( ^ [A:${typ.pretty}>$worldTypeName>$$o, W:$worldTypeName]: ! [X:${typ.pretty}]: ((eiw_${serializeType(typ)} @ X @ W) => (A @ X @ W))))."),
        annotatedTHF(s"thf(mexists_${serializeType(typ)}_type, type, mexists_${serializeType(typ)}: (${typ.pretty} > $worldTypeName > $$o) > $worldTypeName > $$o)."),
        annotatedTHF(s"thf(mexists_${serializeType(typ)}_def, definition, mexists_${serializeType(typ)} = ( ^ [A:${typ.pretty}>$worldTypeName>$$o, W:$worldTypeName]: ? [X:${typ.pretty}]: ((eiw_${serializeType(typ)} @ X @ W) & (A @ X @ W)))).")
      )
    }

    private[this] def polyIndexedVaryQuantifierTPTPDef(): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      Seq(
        annotatedTHF(s"thf(mforall_vary_type, type, mforall_vary: !>[T:$$tType]: ((T > $worldTypeName > $$o) > $worldTypeName > $$o))."),
        annotatedTHF(s"thf(mforall_vary_def, definition, mforall_vary = ( ^ [T:$$tType, A:T>$worldTypeName>$$o, W:$worldTypeName]: ! [X:T]: ((eiw @ T @ X @ W) => (A @ X @ W))))."),
        annotatedTHF(s"thf(mexists_vary_type, type, mexists_vary: !>[T:$$tType]: ((T > $worldTypeName > $$o) > $worldTypeName > $$o))."),
        annotatedTHF(s"thf(mexists_vary_def, definition, mexists_vary = ( ^ [T:$$tType, A:T>$worldTypeName>$$o, W:$worldTypeName]: ? [X:T]: ((eiw @ T @ X @ W) & (A @ X @ W)))).")
      )
    }

    private[this] def polyIndexedConstQuantifierTPTPDef(): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      Seq(
        annotatedTHF(s"thf(mforall_const_type, type, mforall_const: !>[T:$$tType]: ((T > $worldTypeName > $$o) > $worldTypeName > $$o))."),
        annotatedTHF(s"thf(mforall_const_def, definition, mforall_const = ( ^ [T:$$tType, A:T>$worldTypeName>$$o, W:$worldTypeName]: ! [X:T]: (A @ X @ W)))."),
        annotatedTHF(s"thf(mexists_const_type, type, mexists_const: !>[T:$$tType]: ((T > $worldTypeName > $$o) > $worldTypeName > $$o))."),
        annotatedTHF(s"thf(mexists_const_def, definition, mexists_const = ( ^ [T:$$tType, A:T>$worldTypeName>$$o, W:$worldTypeName]: ? [X:T]: (A @ X @ W))).")
      )
    }

    private[this] def indexedExistsInWorldTPTPDef(typ: THF.Type): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      Seq(
        annotatedTHF(s"thf(eiw_${serializeType(typ)}_type, type, eiw_${serializeType(typ)}: (${typ.pretty} > $worldTypeName > $$o))."),
        annotatedTHF(s"thf(eiw_${serializeType(typ)}_nonempty, axiom, ![W:$worldTypeName]: ?[X:${typ.pretty}]: (eiw_${serializeType(typ)} @ X @ W) ).")
      )
    }
    private[this] def indexedConstantExistsInWorldTPTPDef(typ: THF.Type, name: String): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      Seq(
        annotatedTHF(s"thf('eiw_${serializeType(typ)}_${unescapeTPTPName(name)}_exists', axiom, ![W:$worldTypeName]: (eiw_${serializeType(typ)} @ ${escapeAtomicWord(name)} @ W) ).")
      )
    }

    private[this] def indexedCumulativeExistsInWorldTPTPDef(typ: THF.Type): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      if (isMultiModal) Seq(annotatedTHF(s"thf(eiw_${serializeType(typ)}_cumul, axiom, ![Index:$indexTypeName, W:$worldTypeName, V:$worldTypeName, X:${typ.pretty}]: (((eiw_${serializeType(typ)} @ X @ W) & (mrel @ Index @ W @ V)) => (eiw_${serializeType(typ)} @ X @ V)))."))
      else Seq(annotatedTHF(s"thf(eiw_${serializeType(typ)}_cumul, axiom, ![W:$worldTypeName, V:$worldTypeName, X:${typ.pretty}]: (((eiw_${serializeType(typ)} @ X @ W) & (mrel @ W @ V)) => (eiw_${serializeType(typ)} @ X @ V)))."))
    }
    private[this] def indexedDecreasingExistsInWorldTPTPDef(typ: THF.Type): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      if (isMultiModal) Seq(annotatedTHF(s"thf(eiw_${serializeType(typ)}_decr, axiom, ![Index: $indexTypeName, W:$worldTypeName, V:$worldTypeName, X:${typ.pretty}]: (((eiw_${serializeType(typ)} @ X @ W) & (mrel @ Index @ V @ W)) => (eiw_${serializeType(typ)} @ X @ V)))."))
      else Seq(annotatedTHF(s"thf(eiw_${serializeType(typ)}_decr, axiom, ![W:$worldTypeName, V:$worldTypeName, X:${typ.pretty}]: (((eiw_${serializeType(typ)} @ X @ W) & (mrel @ V @ W)) => (eiw_${serializeType(typ)} @ X @ V)))."))
    }

    private[this] def indexedConverseBarcanFormulaTPTPDef(typ: THF.Type): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.{annotatedTHF, thf}
      if (isMultiModal) {
          val box = "[#I]"
          val formula = convertFormula(thf(s"($box @ (![X:${typ.pretty}]: (P @ X))) => (![X:${typ.pretty}]: ($box @ (P @ X)))")).pretty
          Seq(annotatedTHF(s"thf(cbf_${serializeType(typ)}, axiom, ![I: $indexTypeName, P:${typ.pretty} > ($worldTypeName>$$o)]: (mglobal @ ($formula)))."))
      } else {
        val formula = convertFormula(thf(s"($$box @ (![X:${typ.pretty}]: (P @ X))) => (![X:${typ.pretty}]: ($$box @ (P @ X)))")).pretty
        Seq(annotatedTHF(s"thf(cbf_${serializeType(typ)}, axiom, ![P:${typ.pretty} > ($worldTypeName>$$o)]: (mglobal @ ($formula)))."))
      }
    }

    private[this] def indexedBarcanFormulaTPTPDef(typ: THF.Type): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.{annotatedTHF, thf}
      if (isMultiModal) {
         val box = "[#I]"
         val formula = convertFormula(thf(s"(![X:${typ.pretty}]: ($box @ (P @ X))) => ($box @ (![X:${typ.pretty}]: (P @ X)))")).pretty
         Seq(annotatedTHF(s"thf(bf_${serializeType(typ)}, axiom, ![I: $indexTypeName, P:${typ.pretty} > ($worldTypeName>$$o)]: (mglobal @ ($formula)))."))
      } else {
        val formula = convertFormula(thf(s"(![X:${typ.pretty}]: ($$box @ (P @ X))) => ($$box @ (![X:${typ.pretty}]: (P @ X)))")).pretty
        Seq(annotatedTHF(s"thf(bf_${serializeType(typ)}, axiom, ![P:${typ.pretty} > ($worldTypeName>$$o)]: (mglobal @ ($formula)))."))
      }
    }

    private[this] def polyIndexedExistsInWorldTPTPDef(): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      Seq(
        annotatedTHF(s"thf(eiw_type, type, eiw: !>[T:$$tType]: (T > $worldTypeName > $$o))."),
        annotatedTHF(s"thf(eiw_nonempty, axioms, ![T:$$tType, W:$worldTypeName]: ?[X:T]: (eiw @ T @ X @ W) ).")
      )
    }
    private[this] def polyIndexedConstantExistsInWorldTPTPDef(typ: THF.Type, name: String): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      Seq(
        annotatedTHF(s"thf('eiw_${unescapeTPTPName(name)}_exists', axiom, ![W:$worldTypeName]: (eiw @ ${typ.pretty} @ ${escapeAtomicWord(name)} @ W) ).")
      )
    }
    private[this] def polyIndexedCumulativeExistsInWorldTPTPDef(typ: THF.Type): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      if (isMultiModal) {
        Seq(
          annotatedTHF(s"thf(eiw_${serializeType(typ)}_cumul, axiom, ![T:$$tType, R:T, W:$worldTypeName, V:$worldTypeName, X:${typ.pretty}]: (((eiw @ ${typ.pretty} @ X @ W) & (mrel @ T @ R @ W @ V)) => (eiw @ ${typ.pretty} @ X @ V))).")
        )
      } else {
        Seq(
          annotatedTHF(s"thf(eiw_${serializeType(typ)}_cumul, axiom, ![W:$worldTypeName, V:$worldTypeName, X:${typ.pretty}]: (((eiw @ ${typ.pretty} @ X @ W) & (mrel @ W @ V)) => (eiw @ ${typ.pretty} @ X @ V))).")
        )
      }
    }
    private[this] def polyIndexedDecreasingExistsInWorldTPTPDef(typ: THF.Type): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      if (isMultiModal) {
        Seq(
          annotatedTHF(s"thf(eiw_${serializeType(typ)}_cumul, axiom, ![T:$$tType, R:T, W:$worldTypeName, V:$worldTypeName, X:${typ.pretty}]: (((eiw @ ${typ.pretty} @ X @ W) & (mrel @ T @ R @ V @ W)) => (eiw @ ${typ.pretty} @ X @ V))).")
        )
      } else {
        Seq(
          annotatedTHF(s"thf(eiw_${serializeType(typ)}_cumul, axiom, ![W:$worldTypeName, V:$worldTypeName, X:${typ.pretty}]: (((eiw @ ${typ.pretty} @ X @ W) & (mrel @ V @ W)) => (eiw @ ${typ.pretty} @ X @ V))).")
        )
      }
    }

    lazy val semanticAxiomTable: Map[String, Option[TPTP.AnnotatedFormula]] = {
      import modules.input.TPTPParser.annotatedTHF
      Map(
        "$modal_axiom_K" -> None,
        "$modal_axiom_T" -> Some(annotatedTHF(
          s"thf(mrel_reflexive, axiom, ![W:$worldTypeName]: (mrel @ W @ W))."
          )),
        "$modal_axiom_B" -> Some(annotatedTHF(
          s"thf(mrel_symmetric, axiom, ![W:$worldTypeName, V:$worldTypeName]: ((mrel @ W @ V) => (mrel @ V @ W)))."
        )),
        "$modal_axiom_D" -> Some(annotatedTHF(
          s"thf(mrel_serial, axiom, ![W:$worldTypeName]: ?[V:$worldTypeName]: (mrel @ W @ V))."
        )),
        "$modal_axiom_4" -> Some(annotatedTHF(
          s"thf(mrel_transitive, axiom, ![W:$worldTypeName,V:$worldTypeName,U:$worldTypeName]: (((mrel @ W @ V) & (mrel @ V @ U)) => (mrel @ W @ U)))."
        )),
        "$modal_axiom_5" -> Some(annotatedTHF(
          s"thf(mrel_euclidean, axiom, ![W:$worldTypeName,V:$worldTypeName,U:$worldTypeName]: (((mrel @ W @ U) & (mrel @ W @ V)) => (mrel @ U @ V)))."
        )),
        "$modal_axiom_C4" -> Some(annotatedTHF(
          s"thf(mrel_dense, axiom, ![W:$worldTypeName,U:$worldTypeName]: ((mrel @ W @ U) => (? [V:$worldTypeName]: ((mrel @ W @ V) & (mrel @ V @ U)))))."
        )),
        "$modal_axiom_CD" -> Some(annotatedTHF(
          s"thf(mrel_functional, axiom, ![W:$worldTypeName,V:$worldTypeName,U:$worldTypeName]: (((mrel @ W @ U) & (mrel @ W @ V)) => (U = V)))."
        )),
        "$modal_axiom_S5U" -> Some(annotatedTHF(
          s"thf(mrel_universal, axiom, ![W:$worldTypeName,V:$worldTypeName]: (mrel @ W @ V))."
        ))
        // TODO: More axiom schemes
      )
    }
    lazy val syntacticAxiomTable: Map[String, Option[TPTP.AnnotatedFormula]] = {
      import modules.input.TPTPParser.{annotatedTHF, thf}

      Map(
        "$modal_axiom_K" -> None,
        "$modal_axiom_T" -> {
          val formula = convertFormula(thf("($box @ Phi) => Phi")).pretty
          Some(annotatedTHF(
            s"thf(mrel_reflexive, axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
          ))
        },
        "$modal_axiom_B" -> {
          val formula = convertFormula(thf("Phi => ($box @ ($dia @ Phi))")).pretty
          Some(annotatedTHF(
            s"thf(mrel_symmetric, axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
          ))
        },
        "$modal_axiom_D" -> {
          val formula = convertFormula(thf("($box @ Phi) => ($dia @ Phi)")).pretty
          Some(annotatedTHF(
            s"thf(mrel_serial, axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
          ))
        },
        "$modal_axiom_4" -> {
          val formula = convertFormula(thf("($box @ Phi) => ($box @ ($box @ Phi))")).pretty
          Some(annotatedTHF(
            s"thf(mrel_transitive, axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
          ))
        },
        "$modal_axiom_5" -> {
          val formula = convertFormula(thf("($dia @ Phi) => ($box @ ($dia @ Phi))")).pretty
          Some(annotatedTHF(
            s"thf(mrel_euclidean, axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
          ))
        },
        "$modal_axiom_C4" -> {
          val formula = convertFormula(thf("($box @ ($box @ Phi)) => ($box @ Phi)")).pretty
          Some(annotatedTHF(
            s"thf(mrel_dense, axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
          ))
        },
        "$modal_axiom_CD" -> {
          val formula = convertFormula(thf("($dia @ Phi) => ($box @ Phi)")).pretty
          Some(annotatedTHF(
            s"thf(mrel_functional, axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
          ))
        },
        "$modal_axiom_GL" -> {
          val formula = convertFormula(thf("($box @ (($box @ Phi) => Phi)) => ($box @ Phi)")).pretty
          Some(annotatedTHF(
            s"thf(mrel_gl, axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
          ))
        },
        "$modal_axiom_GRZ" -> {
          val formula = convertFormula(thf("($box @ (($box @ (Phi => ($box @ Phi))) => Phi)) => Phi")).pretty
          Some(annotatedTHF(
            s"thf(mrel_grz, axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
          ))
        },
        "$modal_axiom_H" -> {
          val formula = convertFormula(thf("($box @ (($box @ Phi) => Psi)) | ($box @ (($box @ Psi) => Phi))")).pretty
          Some(annotatedTHF(
            s"thf(mrel_h, axiom, ![Phi:$worldTypeName>$$o, Psi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
          ))
        },
        "$modal_axiom_M" -> {
          val formula = convertFormula(thf("($box @ ($dia @ Phi)) => ($dia @ ($box @ Phi))")).pretty
          Some(annotatedTHF(
            s"thf(mrel_m, axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
          ))
        },
        "$modal_axiom_G" -> {
          val formula = convertFormula(thf("($dia @ ($box @ Phi)) => ($box @ ($dia @ Phi))")).pretty
          Some(annotatedTHF(
            s"thf(mrel_g, axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
          ))
        }
      )
    }

    lazy val indexedSyntacticAxiomTable: Map[String, Option[THF.Formula => TPTP.AnnotatedFormula]] = {
      import modules.input.TPTPParser.{annotatedTHF, thf}
      Map(
        "$modal_axiom_K" -> None,
        "$modal_axiom_T" -> {
          Some(idx => {
            val box = s"[#${idx.pretty}]"
            val formula = convertFormula(thf(s"($box @ Phi) => Phi")).pretty
            annotatedTHF(
              s"thf('mrel_${unescapeTPTPName(idx.pretty)}_reflexive', axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
            )
          })
        },
        "$modal_axiom_B" -> {
          Some(idx => {
            val box = s"[#${idx.pretty}]"
            val dia = s"<#${idx.pretty}>"
            val formula = convertFormula(thf(s"Phi => ($box @ ($dia @ Phi))")).pretty
            annotatedTHF(
              s"thf('mrel_${unescapeTPTPName(idx.pretty)}_symmetric', axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
            )
          })
        },
        "$modal_axiom_D" -> {
          Some(idx => {
            val box = s"[#${idx.pretty}]"
            val dia = s"<#${idx.pretty}>"
            val formula = convertFormula(thf(s"($box @ Phi) => ($dia @ Phi)")).pretty
            annotatedTHF(
              s"thf('mrel_${unescapeTPTPName(idx.pretty)}_serial', axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
            )
          })
        },
        "$modal_axiom_4" -> {
          Some(idx => {
            val box = s"[#${idx.pretty}]"
            val formula = convertFormula(thf(s"($box @ Phi) => ($box @ ($box @ Phi))")).pretty
            annotatedTHF(
              s"thf('mrel_${unescapeTPTPName(idx.pretty)}_transitive', axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
            )
          })
        },
        "$modal_axiom_5" -> {
          Some(idx => {
            val box = s"[#${idx.pretty}]"
            val dia = s"<#${idx.pretty}>"
            val formula = convertFormula(thf(s"($dia @ Phi) => ($box @ ($dia @ Phi))")).pretty
            annotatedTHF(
              s"thf('mrel_${unescapeTPTPName(idx.pretty)}_euclidean', axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
            )
          })
        },
        "$modal_axiom_C4" -> {
          Some(idx => {
            val box = s"[#${idx.pretty}]"
            val formula = convertFormula(thf(s"($box @ ($box @ Phi)) => ($box @ Phi)")).pretty
            annotatedTHF(
              s"thf('mrel_${unescapeTPTPName(idx.pretty)}_dense', axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
            )
          })
        },
        "$modal_axiom_CD" -> {
          Some(idx => {
            val box = s"[#${idx.pretty}]"
            val dia = s"<#${idx.pretty}>"
            val formula = convertFormula(thf(s"($dia @ Phi) => ($box @ Phi)")).pretty
            annotatedTHF(
              s"thf('mrel_${unescapeTPTPName(idx.pretty)}_functional', axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
            )
          })
        },
        "$modal_axiom_GL" -> {
          Some(idx => {
            val box = s"[#${idx.pretty}]"
            val formula = convertFormula(thf(s"($box @ (($box @ Phi) => Phi)) => ($box @ Phi)")).pretty
            annotatedTHF(
              s"thf('mrel_${unescapeTPTPName(idx.pretty)}_gl', axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
            )
          })
        },
        "$modal_axiom_GRZ" -> {
          Some(idx => {
            val box = s"[#${idx.pretty}]"
            val formula = convertFormula(thf(s"($box @ (($box @ (Phi => ($box @ Phi))) => Phi)) => Phi")).pretty
            annotatedTHF(
              s"thf('mrel_${unescapeTPTPName(idx.pretty)}_grz', axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
            )
          })
        },
        "$modal_axiom_H" -> {
          Some(idx => {
            val box = s"[#${idx.pretty}]"
            val formula = convertFormula(thf(s"($box @ (($box @ Phi) => Psi)) | ($box @ (($box @ Psi) => Phi))")).pretty
            annotatedTHF(
              s"thf('mrel_${unescapeTPTPName(idx.pretty)}_h', axiom, ![Phi:$worldTypeName>$$o, Psi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
            )
          })
        },
        "$modal_axiom_M" -> {
          Some(idx => {
            val box = s"[#${idx.pretty}]"
            val dia = s"<#${idx.pretty}>"
            val formula = convertFormula(thf(s"($box @ ($dia @ Phi)) => ($dia @ ($box @ Phi))")).pretty
            annotatedTHF(
              s"thf('mrel_${unescapeTPTPName(idx.pretty)}_m', axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
            )
          })
        },
        "$modal_axiom_G" -> {
          Some(idx => {
            val box = s"[#${idx.pretty}]"
            val dia = s"<#${idx.pretty}>"
            val formula = convertFormula(thf(s"($dia @ ($box @ Phi)) => ($box @ ($dia @ Phi))")).pretty
            annotatedTHF(
              s"thf('mrel_${unescapeTPTPName(idx.pretty)}_g', axiom, ![Phi:$worldTypeName>$$o]: (mglobal @ ($formula)))."
            )
          })
        }
      )
    }

    lazy val indexedSemanticAxiomTable: Map[String, Option[THF.Formula => TPTP.AnnotatedFormula]] = {
      import modules.input.TPTPParser.annotatedTHF
      Map(
        "$modal_axiom_K" -> None,
        "$modal_axiom_T" -> Some(idx => annotatedTHF(
          s"thf('mrel_${unescapeTPTPName(idx.pretty)}_reflexive', axiom, ![W:$worldTypeName]: (mrel @ ${idx.pretty} @ W @ W))."
        )),
        "$modal_axiom_B" -> Some(idx => annotatedTHF(
          s"thf('mrel_${unescapeTPTPName(idx.pretty)}_symmetric', axiom, ![W:$worldTypeName, V:$worldTypeName]: ((mrel @ ${idx.pretty} @ W @ V) => (mrel @ ${idx.pretty} @ V @ W)))."
        )),
        "$modal_axiom_D" -> Some(idx => annotatedTHF(
          s"thf('mrel_${unescapeTPTPName(idx.pretty)}_serial', axiom, ![W:$worldTypeName]: ?[V:$worldTypeName]: (mrel @ ${idx.pretty} @ W @ V))."
        )),
        "$modal_axiom_4" -> Some(idx => annotatedTHF(
          s"thf('mrel_${unescapeTPTPName(idx.pretty)}_transitive', axiom, ![W:$worldTypeName,V:$worldTypeName,U:$worldTypeName]: (((mrel @ ${idx.pretty} @ W @ V) & (mrel @ ${idx.pretty} @ V @ U)) => (mrel @ ${idx.pretty} @ W @ U)))."
        )),
        "$modal_axiom_5" -> Some(idx => annotatedTHF(
          s"thf('mrel_${unescapeTPTPName(idx.pretty)}_euclidean', axiom, ![W:$worldTypeName,V:$worldTypeName,U:$worldTypeName]: (((mrel @ ${idx.pretty} @ W @ U) & (mrel @ ${idx.pretty} @ W @ V)) => (mrel @ ${idx.pretty} @ U @ V)))."
        )),
        "$modal_axiom_C4" -> Some(idx => annotatedTHF(
          s"thf('mrel_${unescapeTPTPName(idx.pretty)}_dense', axiom, ![W:$worldTypeName,U:$worldTypeName]: ((mrel @ ${idx.pretty} @ W @ U) => (? [V:$worldTypeName]: ((mrel @ ${idx.pretty} @ W @ V) & (mrel @ ${idx.pretty} @ V @ U)))))."
        )),
        "$modal_axiom_CD" -> Some(idx => annotatedTHF(
          s"thf('mrel_${unescapeTPTPName(idx.pretty)}_functional', axiom, ![W:$worldTypeName,V:$worldTypeName,U:$worldTypeName]: (((mrel @ ${idx.pretty} @ W @ U) & (mrel @ ${idx.pretty} @ W @ V)) => (U = V)))."
        )),
        "$modal_axiom_S5U" -> Some(idx => annotatedTHF(
          s"thf('mrel_${unescapeTPTPName(idx.pretty)}_universal', axiom, ![W:$worldTypeName,V:$worldTypeName]: (mrel @ ${idx.pretty} @ W @ V))."
        ))
        // TODO: More axiom schemes
      )
    }

    private def isModalAxiomName(name: String): Boolean = name.startsWith("$modal_axiom_")
    private def isModalSystemName(name: String): Boolean = name.startsWith("$modal_system_")
    lazy val modalSystemTable: Map[String, Seq[String]] = Map(
      "$modal_system_K" -> Seq("$modal_axiom_K"),
      "$modal_system_K4" -> Seq("$modal_axiom_K", "$modal_axiom_4"),
      "$modal_system_K5" -> Seq("$modal_axiom_K", "$modal_axiom_5"),
      "$modal_system_KB" -> Seq("$modal_axiom_K", "$modal_axiom_B"),
      "$modal_system_K45" -> Seq("$modal_axiom_K", "$modal_axiom_4", "$modal_axiom_5"),
      "$modal_system_KB5" -> Seq("$modal_axiom_K", "$modal_axiom_B", "$modal_axiom_5"),
      "$modal_system_D" -> Seq("$modal_axiom_K", "$modal_axiom_D"),
      "$modal_system_D4" -> Seq("$modal_axiom_K", "$modal_axiom_D", "$modal_axiom_4"),
      "$modal_system_D5" -> Seq("$modal_axiom_K", "$modal_axiom_D", "$modal_axiom_5"),
      "$modal_system_D45" -> Seq("$modal_axiom_K", "$modal_axiom_D", "$modal_axiom_4", "$modal_axiom_5"),
      "$modal_system_DB" -> Seq("$modal_axiom_K", "$modal_axiom_D", "$modal_axiom_B"),
      "$modal_system_T" -> Seq("$modal_axiom_K", "$modal_axiom_T"),
      "$modal_system_B" -> Seq("$modal_axiom_K", "$modal_axiom_T", "$modal_axiom_B"),
      "$modal_system_S4" -> Seq("$modal_axiom_K", "$modal_axiom_T", "$modal_axiom_4"),
      "$modal_system_S5" -> Seq("$modal_axiom_K", "$modal_axiom_T", "$modal_axiom_5"),
      "$modal_system_S5U" -> Seq("$modal_axiom_S5U"),
      "$modal_system_K4W" -> Seq("$modal_axiom_K", "$modal_axiom_GL"),
      "$modal_system_4_1" -> Seq("$modal_axiom_K", "$modal_axiom_T", "$modal_axiom_4", "$modal_axiom_H"),
      "$modal_system_4_2" -> Seq("$modal_axiom_K", "$modal_axiom_T", "$modal_axiom_4", "$modal_axiom_M"),
      "$modal_system_4_3" -> Seq("$modal_axiom_K", "$modal_axiom_T", "$modal_axiom_4" ,"$modal_axiom_G"),
      "$modal_system_Grz" -> Seq("$modal_axiom_K", "$modal_axiom_Grz"),
      "$modal_system_GL" -> Seq("$modal_axiom_K", "$modal_axiom_GL")
    )

    //////////////////////////////////////////////////////////////////////
    // Logic specification parsing
    //////////////////////////////////////////////////////////////////////

    private[this] def createState(spec: TPTP.AnnotatedFormula): Unit = {
      assert(spec.role == "logic")
      spec.formula match {
        case THF.Logical(THF.BinaryFormula(THF.==, THF.FunctionTerm(name, Seq()),THF.Tuple(spec0))) if Seq("$modal", "$alethic_modal", "$deontic_modal", "$epistemic_modal") contains name =>
          spec0 foreach {
            case THF.BinaryFormula(THF.==, THF.FunctionTerm(propertyName, Seq()), rhs) =>
              propertyName match {
                case "$constants" =>
                  val (default, map) = parseRHS(rhs)
                  default match {
                    case Some("$rigid") => rigidityMap = rigidityMap.withDefaultValue(Rigid)
                    case Some("$flexible") => rigidityMap = rigidityMap.withDefaultValue(Flexible)
                    case None => // Do nothing, no default
                    case _ => throw new EmbeddingException(s"Unrecognized semantics option: '$default'")
                  }
                  map foreach { case (name, rigidity) =>
                    rigidity match {
                      case "$rigid" => rigidityMap += (name -> Rigid)
                      case "$flexible" => rigidityMap += (name -> Flexible)
                      case _ => throw new EmbeddingException(s"Unrecognized semantics option: '$rigidity'")
                    }
                  }
                case "$quantification" =>
                  val (default, map) = parseRHS(rhs)
                  default match {
                    case Some("$constant") => domainMap = domainMap.withDefaultValue(ConstantDomain)
                    case Some("$varying") => domainMap = domainMap.withDefaultValue(VaryingDomain)
                    case Some("$cumulative") => domainMap = domainMap.withDefaultValue(CumulativeDomain)
                    case Some("$decreasing") => domainMap = domainMap.withDefaultValue(DecreasingDomain)
                    case None => // Do nothing, no default
                    case _ => throw new EmbeddingException(s"Unrecognized semantics option: '$default'")
                  }
                  map foreach { case (name, quantification) =>
                    quantification match {
                      case "$constant" => domainMap += (name -> ConstantDomain)
                      case "$varying" => domainMap += (name -> VaryingDomain)
                      case "$cumulative" => domainMap += (name -> CumulativeDomain)
                      case "$decreasing" => domainMap += (name -> DecreasingDomain)
                      case _ => throw new EmbeddingException(s"Unrecognized semantics option: '$quantification'")
                    }
                  }
                case "$modalities" => val (default, map) = parseListRHS(rhs)
                  if (default.nonEmpty) {
                    if (default.forall(spec => isModalSystemName(spec) || isModalAxiomName(spec)))
                      state.setDefault(MODALS, default)
                    else throw new EmbeddingException(s"Unknown modality specification: ${default.mkString("[",",", "]")}")
                  }
                  map foreach { case (lhs, modalspec) =>
                    val index0 = lhs match {
                      case THF.ConnectiveTerm(THF.NonclassicalBox(Some(index))) => index
                      case THF.ConnectiveTerm(THF.NonclassicalLongOperator(cname, Seq(Left(index))))
                       if Seq("$box", "$necessary" , "$obligatory" , "$knows").contains(cname) => index
                      case _ => throw new EmbeddingException(s"Modality specification did not start with '[#idx] == ...' or '{#box(#idx)} == ...'.")
                    }
                    val index = escapeModalIndex(index0)
                    if (modalspec.nonEmpty) {
                      if (modalspec.forall(spec => isModalSystemName(spec) || isModalAxiomName(spec)))
                        state(MODALS) += (index -> modalspec)
                      else throw new EmbeddingException(s"Unknown modality specification: ${modalspec.mkString("[",",", "]")}")
                    }
                  }
                case _ => throw new EmbeddingException(s"Unknown modal logic semantics property '$propertyName'")
              }
            case s => throw new EmbeddingException(s"Malformed logic specification entry: ${s.pretty}")
          }
        case _ => throw new EmbeddingException(s"Malformed logic specification entry: ${spec.pretty}")
      }
    }

  }

}
