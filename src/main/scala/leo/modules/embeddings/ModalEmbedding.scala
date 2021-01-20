package leo
package modules
package embeddings

import datastructures.{FlexMap, TPTP}
import TPTP.{AnnotatedFormula, THF}
import ModalEmbeddingOption._

object ModalEmbedding extends Embedding[ModalEmbeddingOption] {

  final def apply(problem: Seq[AnnotatedFormula],
                  embeddingOptions: Set[ModalEmbeddingOption] = Set.empty): Seq[AnnotatedFormula] =
    new ModalEmbeddingImpl(problem, embeddingOptions).apply()

  /////////////////////////////////////////////////////////////////////////////////
  /////////////////////////////////////////////////////////////////////////////////
  // The embedding
  /////////////////////////////////////////////////////////////////////////////////
  /////////////////////////////////////////////////////////////////////////////////
  private[this] final class ModalEmbeddingImpl(problem: Seq[AnnotatedFormula], embeddingOptions: Set[ModalEmbeddingOption]) {

    ///////////////////////////////////////////////////////////////////
    private final val state = FlexMap.apply()

    // Semantics dimensions
    private final val RIGIDITY_RIGID = true
    private final val RIGIDITY_FLEXIBLE = false
    private final val RIGIDITY = state.createKey[String, Boolean]()
    state(RIGIDITY) += ("$o" -> RIGIDITY_FLEXIBLE)

    private final val CONSEQUENCE_GLOBAL = true
    private final val CONSEQUENCE_LOCAL = false
    private final val CONSEQUENCE = state.createKey[String, Boolean]()

    private final val DOMAIN_CONSTANT = 0
    private final val DOMAIN_VARYING = 1
    private final val DOMAIN_CUMULATIVE = 2
    private final val DOMAIN_DECREASING = 3
    private final val DOMAIN = state.createKey[String, Int]()

    private final val MODALS = state.createKey[String, Seq[String]]()
    ////////////////////////////////////////////////////////////////////
    // Embedding options
    private val polymorphic: Boolean = embeddingOptions.contains(POLYMORPHIC) // default monomorphic

    private final val EMBEDDING_SYNTACTICAL = true
    private final val EMBEDDING_SEMANTICAL = false
    private val embeddingType: Boolean = embeddingOptions.contains(SYNTACTICAL) // default semantical
    ////////////////////////////////////////////////////////////////////

    def apply(): Seq[AnnotatedFormula] = {
      val (spec, remainingFormulas) = splitInput(problem)
      createState(spec)
      val (typeFormulas, nonTypeFormulas) = remainingFormulas.partition(_.role == "type")
      val convertedTypeFormulas = typeFormulas.map(convertTypeFormula)
      val convertedOtherFormulas = nonTypeFormulas.map(convertAnnotatedFormula)
      val generatedMetaFormulas: Seq[AnnotatedFormula] = generateMetaFormulas()

      generatedMetaFormulas ++ convertedTypeFormulas ++ convertedOtherFormulas
    }

    def convertAnnotatedFormula(formula: AnnotatedFormula): AnnotatedFormula = {
      formula match {
        case TPTP.THFAnnotated(name, role, TPTP.THF.Logical(formula), annotations) =>
          val convertedFormula0 = convertFormula(formula)
          val convertedFormula = state(CONSEQUENCE)(name) match {
            case CONSEQUENCE_GLOBAL => THF.BinaryFormula(THF.App, mglobal, convertedFormula0)
            case CONSEQUENCE_LOCAL => THF.BinaryFormula(THF.App, mlocal, convertedFormula0)
          }
          TPTP.THFAnnotated(name, role, TPTP.THF.Logical(convertedFormula), annotations)
        case _ => throw new EmbeddingException(s"Only embedding of THF files supported.")
      }
    }

    private def mkLambda(variable: THF.TypedVariable, body: THF.Formula): THF.Formula = {
      THF.QuantifiedFormula(THF.^, Seq(variable), body)
    }

    private def mkSingleQuantified(quantifier: THF.Quantifier)(variable: THF.TypedVariable, acc: THF.Formula): THF.Formula = {
      val convertedVariable: THF.TypedVariable = (variable._1, convertType(variable._2))
      quantifierType(convertedVariable._2)
      val convertedQuantifier: THF.Formula =
        if (polymorphic) THF.BinaryFormula(THF.App, convertQuantifier(quantifier, variable._2, convertedVariable._2), convertedVariable._2)
        else convertQuantifier(quantifier, variable._2, convertedVariable._2)
      THF.BinaryFormula(THF.App, convertedQuantifier, mkLambda(convertedVariable, acc))
    }

    private def convertFormula(formula: TPTP.THF.Formula): TPTP.THF.Formula = {
      import TPTP.THF.App
      formula match {
        case THF.FunctionTerm("$box", Seq()) => mbox
        case THF.FunctionTerm("$dia", Seq()) => mdia

        case THF.FunctionTerm(f, args) =>
          val convertedArgs = args.map(convertFormula)
          THF.FunctionTerm(f, convertedArgs)

        case THF.QuantifiedFormula(quantifier, variableList, body) =>
          val convertedBody = convertFormula(body)
          variableList.foldRight(convertedBody)(mkSingleQuantified(quantifier))

        case THF.Variable(_) => formula

        case THF.UnaryFormula(connective, body) =>
          val convertedConnective: TPTP.THF.Formula = convertConnective(connective)
          val convertedBody: TPTP.THF.Formula = convertFormula(body)
          THF.BinaryFormula(App, convertedConnective, convertedBody)

        // polymorphic box and diamond cases
        case THF.BinaryFormula(App, THF.BinaryFormula(App, THF.FunctionTerm("$box_P", Seq()), tyArg), index) =>
          mboxIndexed(index, tyArg)
        case THF.BinaryFormula(App, THF.BinaryFormula(App, THF.FunctionTerm("$dia_P", Seq()), tyArg), index) =>
          mdiaIndexed(index, tyArg)

        // Non-poly modal operators or standard application
        case THF.BinaryFormula(App, left, right) =>
          left match {
            case THF.FunctionTerm("$box_int", Seq()) =>
              right match {
                case nt@THF.NumberTerm(TPTP.Integer(_)) => mboxIndexed(nt, THF.FunctionTerm("$int", Seq.empty))
                case _ => throw new EmbeddingException(s"Index of $$box_int was not a number, but '${right.pretty}'.")
              }
            case THF.FunctionTerm("$dia_int", Seq()) =>
              right match {
                case nt@THF.NumberTerm(TPTP.Integer(_)) => mdiaIndexed(nt, THF.FunctionTerm("$int", Seq.empty))
                case _ => throw new EmbeddingException(s"Index of $$box_int was not a number, but '${right.pretty}'.")
              }
            case THF.FunctionTerm("$box_i", Seq()) =>
              right match {
                case ft@THF.FunctionTerm(_, Seq()) => mboxIndexed(ft, THF.FunctionTerm("$i", Seq.empty))
                case _ => throw new EmbeddingException(s"Index of $$box_i was not a symbol/functor, but '${right.pretty}'.")
              }
            case THF.FunctionTerm("$dia_i", Seq()) =>
              right match {
                case ft@THF.FunctionTerm(_, Seq()) => mdiaIndexed(ft, THF.FunctionTerm("$i", Seq.empty))
                case _ => throw new EmbeddingException(s"Index of $$dia_i was not a symbol/functor, but '${right.pretty}'.")
              }
            case _ =>
              val convertedLeft: TPTP.THF.Formula = convertFormula(left)
              val convertedRight: TPTP.THF.Formula = convertFormula(right)
              THF.BinaryFormula(App, convertedLeft, convertedRight)
          }

        case THF.BinaryFormula(connective, left, right) =>
          val convertedConnective: TPTP.THF.Formula = convertConnective(connective)
          val convertedLeft: TPTP.THF.Formula = convertFormula(left)
          val convertedRight: TPTP.THF.Formula = convertFormula(right)
          THF.BinaryFormula(App, convertedConnective, THF.BinaryFormula(App, convertedLeft, convertedRight))

        case THF.ConnectiveTerm(conn) => convertConnective(conn)

        case THF.Tuple(elements) =>
          val convertedElements: Seq[TPTP.THF.Formula] = elements.map(convertFormula)
          THF.Tuple(convertedElements)

        case THF.ConditionalTerm(condition, thn, els) =>
          val convertedCondition: TPTP.THF.Formula = convertFormula(condition)
          val convertedThn: TPTP.THF.Formula = convertFormula(thn)
          val convertedEls: TPTP.THF.Formula = convertFormula(els)
          THF.ConditionalTerm(convertedCondition, convertedThn, convertedEls)

        case THF.LetTerm(typing, binding, body) => // This will probably change as the parse tree of LetTerms will still change.
          val convertedTyping: Map[String, TPTP.THF.Type] = typing.map(a => (a._1, convertType(a._2)))
          val convertedBinding: Seq[(TPTP.THF.Formula, TPTP.THF.Formula)]  = binding.map(a => (convertFormula(a._1), convertFormula(a._2)))
          val convertedBody = convertFormula(body)
          THF.LetTerm(convertedTyping, convertedBinding, convertedBody)
        case THF.DistinctObject(_) => formula
        case THF.NumberTerm(_) => formula
      }
    }

    private def convertConnective(connective: TPTP.THF.Connective): THF.Formula = {
      val name = connective match {
        case THF.~ => "mnot"
        case THF.!! => "mforall"
        case THF.?? => "mexists"
        case THF.@@+ => "mchoice"
        case THF.@@- => "mdesc"
        case THF.@= => "meq"
        case THF.Eq => "meq"
        case THF.Neq => "mneq"
        case THF.<=> => "mequiv"
        case THF.Impl => "mimpl"
        case THF.<= => "mif"
        case THF.<~> => "mniff"
        case THF.~| => "mnor"
        case THF.~& => "mnand"
        case THF.| => "mor"
        case THF.& => "mand"
        case THF.App => throw new EmbeddingException(s"An unexpected error occurred, this is considered a bug. Please report it :-)")
        case THF.:= => throw new EmbeddingException(s"Unexpected assignment operator used as connective.")
        case _ => throw new EmbeddingException(s"Unexpected type constructor used as connective: '${connective.pretty}'")
        //      case THF.FunTyConstructor => ???
        //      case THF.ProductTyConstructor => ???
        //      case THF.SumTyConstructor => ???
      }
      THF.FunctionTerm(name, Seq.empty)
    }

    private def convertQuantifier(quantifier: TPTP.THF.Quantifier, typ: TPTP.THF.Type, convertedType: TPTP.THF.Type): THF.Formula = {
      val name = quantifier match {
        case THF.! =>
          try {
            state(DOMAIN)(typ.pretty) match {
              case DOMAIN_CONSTANT => if (polymorphic) "mforall_const" else s"mforall_${serializeType(convertedType)}"
              case _ => // all three other cases
                if (polymorphic) "mforall_vary" else s"mforall_${serializeType(convertedType)}"
            }
          } catch {
            case _: NoSuchElementException => throw new EmbeddingException(s"Undefined domain semantics for type '${typ.pretty}'. Maybe a default value was omitted?")
          }

        case THF.? =>
          try {
            state(DOMAIN)(typ.pretty) match {
              case DOMAIN_CONSTANT => if (polymorphic) "mexists_const" else s"mexists_${serializeType(convertedType)}"
              case _ => // all three other cases
                if (polymorphic) "mexists_vary" else s"mexists_${serializeType(convertedType)}"
            }
          } catch {
            case _: NoSuchElementException => throw new EmbeddingException(s"Undefined domain semantics for type '${typ.pretty}'. Maybe a default value was omitted?")
          }
        case THF.^ => "mlambda"
        case THF.@+ => "mchoice"
        case THF.@- => "mdescription"
        case _ => throw new EmbeddingException(s"Unexpected type quantifier used as term quantifier: '${quantifier.pretty}'")
      }
      THF.FunctionTerm(name, Seq.empty)
    }

    private[this] def mbox: THF.Formula = THF.FunctionTerm("mbox", Seq.empty)
    private[this] def mboxIndexed(index: THF.Formula, typ: THF.Type): THF.Formula = {
      // TODO: Switch $int -> otherType here
      multiModal(index, typ)
      if (polymorphic) THF.BinaryFormula(THF.App, THF.BinaryFormula(THF.App, mbox, typ), index)
      else THF.BinaryFormula(THF.App, THF.FunctionTerm(s"mbox_${serializeType(typ)}", Seq.empty), index)
    }
    private[this] def mdia: THF.Formula = THF.FunctionTerm("mdia", Seq.empty)
    private[this] def mdiaIndexed(index: THF.Formula, typ: THF.Type): THF.Formula = {
      // TODO: Switch $int -> otherType here
      multiModal(index, typ)
      if (polymorphic) THF.BinaryFormula(THF.App, THF.BinaryFormula(THF.App, mdia, typ), index)
      else THF.BinaryFormula(THF.App, THF.FunctionTerm(s"mdia_${serializeType(typ)}", Seq.empty), index)
    }

    private[this] def mglobal: THF.Formula = THF.FunctionTerm("mglobal", Seq.empty)
    private[this] def mlocal: THF.Formula =  THF.FunctionTerm("mlocal", Seq.empty)


    private def convertTypeFormula(formula: AnnotatedFormula): AnnotatedFormula = {
      formula match {
        case TPTP.THFAnnotated(name, role, TPTP.THF.Typing(typeName, typ), annotations) =>
          val convertedTyping = TPTP.THF.Typing(typeName, convertType(typ))
          TPTP.THFAnnotated(name, role, convertedTyping, annotations)
        case TPTP.THFAnnotated(_, _, _, _) => throw new EmbeddingException(s"Unexpected error: Type conversion called on non-type-statement.")
        case _ => throw new EmbeddingException(s"Only embedding of THF files supported.")
      }
    }

    private def convertType(typ: TPTP.THF.Type): TPTP.THF.Type = {
      typ match {
        case THF.FunctionTerm(f, args) =>
          val convertedArgs = args.map(convertType)
          if (state(RIGIDITY)(f)) THF.FunctionTerm(f, convertedArgs)
          else THF.BinaryFormula(THF.FunTyConstructor, THF.FunctionTerm(worldTypeName, Seq.empty), THF.FunctionTerm(f, convertedArgs))

        case THF.BinaryFormula(connective, left, right) =>
          val convertedLeft = convertType(left)
          val convertedRight = convertType(right)
          THF.BinaryFormula(connective, convertedLeft, convertedRight)

        case THF.Tuple(elements) =>
          val convertedElements = elements.map(convertType)
          THF.Tuple(convertedElements)

        case _ => throw new EmbeddingException(s"Unexpected type expression in type: '${typ.pretty}'")
      }
    }

    ///////////////////////////////////////////////////
    // Local embedding state
    ///////////////////////////////////////////////////
    import collection.mutable
    private[this] val modalOperators: mutable.Map[THF.Type, Set[THF.Formula]] = mutable.Map.empty
    private[this] def isMultiModal: Boolean = modalOperators.nonEmpty
    private[this] def multiModal(index: THF.Formula, typ: THF.Type): Unit = {
      val set = modalOperators.getOrElse(typ, Set.empty)
      modalOperators += (typ -> (set + index))
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
      /////////////////////////////////////////////////////////////
      // Then: Introduce mrel (as relation or as collection of relations)
      if (isMultiModal) {
        if (polymorphic) result.append(polyIndexedAccessibilityRelationTPTPDef())
        else {
          modalOperators foreach { case (ty, _) =>
            result.append(indexedAccessibilityRelationTPTPDef(ty))
          }
        }
      } else result.append(simpleAccessibilityRelationTPTPDef())
      /////////////////////////////////////////////////////////////
      // Then: Define mglobal/mlocal
      state.getDefault(CONSEQUENCE) match {
        case Some(consequence) => consequence match { // Add default and the other one if used
          case CONSEQUENCE_GLOBAL =>
            result.appendAll(mglobalTPTPDef())
            if (state(CONSEQUENCE).exists(_._2 == CONSEQUENCE_LOCAL)) result.appendAll(mlocalTPTPDef())
          case CONSEQUENCE_LOCAL =>
            result.appendAll(mlocalTPTPDef())
            if (state(CONSEQUENCE).exists(_._2 == CONSEQUENCE_LOCAL)) result.appendAll(mglobalTPTPDef())
        }
        case None => // Add only those used
          if (state(CONSEQUENCE).exists(_._2 == CONSEQUENCE_LOCAL)) result.appendAll(mlocalTPTPDef())
          if (state(CONSEQUENCE).exists(_._2 == CONSEQUENCE_LOCAL)) result.appendAll(mglobalTPTPDef())
      }
      /////////////////////////////////////////////////////////////
      // Then: Define connectives
      result.appendAll(connectivesTPTPDef())
      /////////////////////////////////////////////////////////////
      // Then: Define modal operators
      if (isMultiModal) {
        if (polymorphic) result.appendAll(polyIndexedModalOperatorsTPTPDef())
        else {
          modalOperators foreach { case (ty, _) =>
            result.appendAll(indexedModalOperatorsTPTPDef(ty))
          }
        }
      } else result.appendAll(simpleModalOperatorsTPTPDef())
      /////////////////////////////////////////////////////////////
      // Then: Give mrel properties (sem/syn)
      // write used properties and assign (if semantical)
      // or write syntactical axioms (if syntactical)
      // TODO
      /////////////////////////////////////////////////////////////
      // Then: Define exist-in-world-predicates and quantifier restrictions (if cumul/decr/vary)
      if (polymorphic) {
        if (quantifierTypes.nonEmpty) {
          if (quantifierTypes.exists(ty => state(DOMAIN)(ty.pretty) != DOMAIN_CONSTANT))
            result.appendAll(polyIndexedExistsInWorldTPTPDef()) // define poly eiw
          quantifierTypes foreach { ty =>
            if (state(DOMAIN)(ty.pretty) == DOMAIN_CUMULATIVE) {
              result.appendAll(polyIndexedCumulativeExistsInWorldTPTPDef(ty)) // define cumul axioms for eiw with that type
            }
            if (state(DOMAIN)(ty.pretty) == DOMAIN_DECREASING) {
              result.appendAll(polyIndexedDecreasingExistsInWorldTPTPDef(ty)) // define decreasing axioms for eiw with that type
            }
          }
        }
      } else {
        quantifierTypes foreach { ty =>
          if (state(DOMAIN)(ty.pretty) != DOMAIN_CONSTANT) {
            result.appendAll(indexedExistsInWorldTPTPDef(ty)) // define eiw with standard axioms
          }
          if (state(DOMAIN)(ty.pretty) == DOMAIN_CUMULATIVE) {
            result.appendAll(indexedCumulativeExistsInWorldTPTPDef(ty)) // define cumul axioms for eiw
          }
          if (state(DOMAIN)(ty.pretty) == DOMAIN_DECREASING) {
            result.appendAll(indexedDecreasingExistsInWorldTPTPDef(ty)) // define decreasing axioms for eiw
          }
        }
      }
      /////////////////////////////////////////////////////////////
      // Then: Define quantifiers (TH0/TH1)
      if (polymorphic) {
        if (quantifierTypes.nonEmpty) {
          if (quantifierTypes.exists(ty => state(DOMAIN)(ty.pretty) == DOMAIN_CONSTANT))
            result.appendAll(polyIndexedConstQuantifierTPTPDef())
          if (quantifierTypes.exists(ty => state(DOMAIN)(ty.pretty) != DOMAIN_CONSTANT))
            result.appendAll(polyIndexedVaryQuantifierTPTPDef())
        }
      } else {
        quantifierTypes foreach { ty =>
          if (state(DOMAIN)(ty.pretty) == DOMAIN_CONSTANT) result.appendAll(indexedConstQuantifierTPTPDef(ty))
          else result.appendAll(indexedVaryQuantifierTPTPDef(ty))
        }
      }
      /////////////////////////////////////////////////////////////
      // Return all
      result.toSeq
    }

    @inline private[this] def worldTypeName: String = "mworld"

    private[this] def worldTypeTPTPDef(): TPTP.AnnotatedFormula = {
      import modules.input.TPTPParser.annotatedTHF
      annotatedTHF(s"thf($worldTypeName, type, $worldTypeName: $$tType).")
    }

    private[this] def simpleAccessibilityRelationTPTPDef(): TPTP.AnnotatedFormula = {
      import modules.input.TPTPParser.annotatedTHF
      annotatedTHF(s"thf(mrel_type, type, mrel: $worldTypeName > $worldTypeName > $$o).")
    }

    private[this] def indexedAccessibilityRelationTPTPDef(typ: THF.Type): TPTP.AnnotatedFormula = {
      import modules.input.TPTPParser.annotatedTHF
      annotatedTHF(s"thf(mrel_${serializeType(typ)}_type, type, mrel_${serializeType(typ)}: ${typ.pretty} > $worldTypeName > $worldTypeName > $$o).")
    }

    private[this] def polyIndexedAccessibilityRelationTPTPDef(): TPTP.AnnotatedFormula = {
      import modules.input.TPTPParser.annotatedTHF
      annotatedTHF(s"thf(mrel_type, type, mrel: !>[T:$$tType]: (T > $worldTypeName > $worldTypeName > $$o)).")
    }

    private[this] def mglobalTPTPDef(): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      Seq(
        annotatedTHF(s"thf(mglobal_type, type, mglobal: ($worldTypeName > $$o) > $$o)."),
        annotatedTHF(s"thf(mglobal_def, type, mglobal = (^ [Phi: $worldTypeName > $$o]: ![W: $worldTypeName]: (Phi @ W)) ).")
      )
    }

    private[this] def mlocalTPTPDef(): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      Seq(
        annotatedTHF(s"thf(mactual_type, type, mactual: $worldTypeName)."),
        annotatedTHF(s"thf(mlocal_type, type, mlocal: ($worldTypeName > $$o) > $$o)."),
        annotatedTHF(s"thf(mlocal_def, type, mlocal = (^ [Phi: $worldTypeName > $$o]: (Phi @ mactual)) ).")
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

    private[this] def indexedModalOperatorsTPTPDef(typ: THF.Type): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      Seq(
        annotatedTHF(s"thf(mbox_${serializeType(typ)}_type, type, mbox_${serializeType(typ)}: ${typ.pretty} > ($worldTypeName>$$o)>$worldTypeName>$$o )."),
        annotatedTHF(s"thf(mbox_${serializeType(typ)}_def, definition, ( mbox_${serializeType(typ)} = (^ [R:${typ.pretty}, Phi:$worldTypeName>$$o,W:$worldTypeName]: ! [V:$worldTypeName]: ( (mrel_${serializeType(typ)} @ R @ W @ V) => (Phi @ V) ))))."),
        annotatedTHF(s"thf(mdia_${serializeType(typ)}_type, type, mdia_${serializeType(typ)}: ${typ.pretty} > ($worldTypeName>$$o)>$worldTypeName>$$o )."),
        annotatedTHF(s"thf(mdia_${serializeType(typ)}_def, definition, ( mdia_${serializeType(typ)} = (^ [R:${typ.pretty}, Phi:$worldTypeName>$$o, W:$worldTypeName]: ?[V:$worldTypeName]: ( (mrel_${serializeType(typ)} @ R @ W @ V) & (Phi @ V) )))).")
      )
    }

    private[this] def polyIndexedModalOperatorsTPTPDef(): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      Seq(
        annotatedTHF(s"thf(mbox_type, type, mbox: !>[T:$$tType]: (T > ($worldTypeName>$$o)>$worldTypeName>$$o) )."),
        annotatedTHF(s"thf(mbox_def, definition, ( mbox = (^ [T:$$tType, R:T, Phi:$worldTypeName>$$o, W:$worldTypeName]: ! [V:$worldTypeName]: ( (mrel @ T @ R @ W @ V) => (Phi @ V) ))))."),
        annotatedTHF(s"thf(mdia_type, type, mdia: !>[T:$$tType]: (T > ($worldTypeName>$$o)>$worldTypeName>$$o) )."),
        annotatedTHF(s"thf(mdia_def, definition, ( mdia = (^ [T:$$tType, R:T, Phi:$worldTypeName>$$o, W:$worldTypeName]: ?[V:$worldTypeName]: ( (mrel @ T @ R @ W @ V) & (Phi @ V) )))).")
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
    private[this] def indexedCumulativeExistsInWorldTPTPDef(typ: THF.Type): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      if (isMultiModal) {
        modalOperators.keySet.map(mrelTy => annotatedTHF(s"thf(eiw_${serializeType(typ)}_cumul, axiom, ![W:$worldTypeName, V:$worldTypeName, X:${typ.pretty}]: (((eiw_${serializeType(typ)} @ X @ W) & (mrel_${serializeType(mrelTy)} @ W @ V)) => (eiw_${serializeType(typ)} @ X @ V))).")).toSeq
      } else {
        Seq(
          annotatedTHF(s"thf(eiw_${serializeType(typ)}_cumul, axiom, ![W:$worldTypeName, V:$worldTypeName, X:${typ.pretty}]: (((eiw_${serializeType(typ)} @ X @ W) & (mrel @ W @ V)) => (eiw_${serializeType(typ)} @ X @ V))).")
        )
      }
    }
    private[this] def indexedDecreasingExistsInWorldTPTPDef(typ: THF.Type): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      if (isMultiModal) {
        modalOperators.keySet.map(mrelTy => annotatedTHF(s"thf(eiw_${serializeType(typ)}_cumul, axiom, ![W:$worldTypeName, V:$worldTypeName, X:${typ.pretty}]: (((eiw_${serializeType(typ)} @ X @ W) & (mrel_${serializeType(mrelTy)} @ V @ W)) => (eiw_${serializeType(typ)} @ X @ V))).")).toSeq
      } else {
        Seq(
          annotatedTHF(s"thf(eiw_${serializeType(typ)}_decr, axiom, ![W:$worldTypeName, V:$worldTypeName, X:${typ.pretty}]: (((eiw_${serializeType(typ)} @ X @ W) & (mrel @ V @ W)) => (eiw_${serializeType(typ)} @ X @ V))).")
        )
      }
    }

    private[this] def polyIndexedExistsInWorldTPTPDef(): Seq[TPTP.AnnotatedFormula] = {
      import modules.input.TPTPParser.annotatedTHF
      Seq(
        annotatedTHF(s"thf(eiw_type, type, eiw: !>[T:$$tType]: (T > $worldTypeName > $$o))."),
        annotatedTHF(s"thf(eiw_nonempty, axioms, ![T:$$tType, W:$worldTypeName]: ?[X:T]: (eiw @ T @ X @ W) ).")
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

    //////////////////////////////////////////////////////////////////////
    // Logic specification parsing
    //////////////////////////////////////////////////////////////////////

    private[this] def createState(spec: TPTP.AnnotatedFormula): Unit = {
      assert(spec.role == "logic")
      spec.formula match {
        case THF.Logical(THF.BinaryFormula(THF.:=, THF.FunctionTerm("$modal", Seq()),THF.Tuple(spec0))) =>
          spec0 foreach {
            case THF.BinaryFormula(THF.:=, THF.FunctionTerm(propertyName, Seq()), rhs) =>
              propertyName match {
                case "$constants" =>
                  val (default, map) = parseRHS(rhs)
                  default match {
                    case Some("$rigid") => state.setDefault(RIGIDITY, RIGIDITY_RIGID)
                    case Some("$flexible") => state.setDefault(RIGIDITY, RIGIDITY_FLEXIBLE)
                    case None => // Do nothing, no default
                    case _ => throw new EmbeddingException(s"Unrecognized semantics option: '$default'")
                  }
                  map foreach { case (name, rigidity) =>
                    rigidity match {
                      case "$rigid" => state(RIGIDITY) += (name -> RIGIDITY_RIGID)
                      case "$flexible" => state(RIGIDITY) += (name -> RIGIDITY_FLEXIBLE)
                      case _ => throw new EmbeddingException(s"Unrecognized semantics option: '$rigidity'")
                    }
                  }
                case "$quantification" =>
                  val (default, map) = parseRHS(rhs)
                  default match {
                    case Some("$constant") => state.setDefault(DOMAIN, DOMAIN_CONSTANT)
                    case Some("$varying") => state.setDefault(DOMAIN, DOMAIN_VARYING)
                    case Some("$cumulative") => state.setDefault(DOMAIN, DOMAIN_CUMULATIVE)
                    case Some("$decreasing") => state.setDefault(DOMAIN, DOMAIN_DECREASING)
                    case None => // Do nothing, no default
                    case _ => throw new EmbeddingException(s"Unrecognized semantics option: '$default'")
                  }
                  map foreach { case (name, quantification) =>
                    quantification match {
                      case "$constant" => state(DOMAIN) += (name -> DOMAIN_CONSTANT)
                      case "$varying" => state(DOMAIN) += (name -> DOMAIN_VARYING)
                      case "$cumulative" => state(DOMAIN) += (name -> DOMAIN_CUMULATIVE)
                      case "$decreasing" => state(DOMAIN) += (name -> DOMAIN_DECREASING)
                      case _ => throw new EmbeddingException(s"Unrecognized semantics option: '$quantification'")
                    }
                  }
                case "$consequence" =>
                  val (default, map) = parseRHS(rhs)
                  default match {
                    case Some("$local") => state.setDefault(CONSEQUENCE, CONSEQUENCE_LOCAL)
                    case Some("$global") => state.setDefault(CONSEQUENCE, CONSEQUENCE_GLOBAL)
                    case None => // Do nothing, no default
                    case _ => throw new EmbeddingException(s"Unrecognized semantics option: '$default'")
                  }
                  map foreach { case (name, consequence) =>
                    consequence match {
                      case "$local" => state(CONSEQUENCE) += (name -> CONSEQUENCE_LOCAL)
                      case "$global" => state(CONSEQUENCE) += (name -> CONSEQUENCE_GLOBAL)
                      case _ => throw new EmbeddingException(s"Unrecognized semantics option: '$consequence'")
                    }
                  }
                case "$modalities" => val (default, map) = parseListRHS(rhs)
                  if (default.nonEmpty) state.setDefault(MODALS, default)
                  map foreach { case (name, modalspec) =>
                    if (modalspec.nonEmpty) state(MODALS) += (name -> modalspec)
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
