// (C) Copyright 2001 Samuele Pedroni

package org.python.compiler;

import org.python.antlr.Visitor;
import org.python.antlr.PythonTree;
import org.python.antlr.ast.*;
import org.python.antlr.base.expr;
import org.python.antlr.base.stmt;
import org.python.core.ParserFacade;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.Stack;
import java.util.List;

public class ScopesCompiler extends Visitor implements ScopeConstants {

    private CompilationContext code_compiler;

    private Stack<ScopeInfo> scopes;
    private ScopeInfo cur = null;
    private Hashtable<PythonTree,ScopeInfo> nodeScopes;

    private int level = 0;
    private int func_level = 0;

    public ScopesCompiler(CompilationContext code_compiler, Hashtable<PythonTree,ScopeInfo> nodeScopes) {
        this.code_compiler = code_compiler;
        this.nodeScopes = nodeScopes;
        scopes = new Stack<ScopeInfo>();
    }

    public void beginScope(String name, int kind, PythonTree node,
            ArgListCompiler ac) {
        if (cur != null) {
            scopes.push(cur);
        }
        if (kind == FUNCSCOPE) {
            func_level++;
        }
        cur = new ScopeInfo(name, node, level++, kind, func_level, ac);
        nodeScopes.put(node, cur);
    }

    public void endScope() throws Exception {
        if (cur.kind == FUNCSCOPE) {
            func_level--;
        }
        level--;
        ScopeInfo up = null;
        if (!scopes.empty()) {
            up = scopes.pop();
        }
        //Go into the stack to find a non class containing scope to use making the closure
        //See PEP 227
        int dist = 1;
        ScopeInfo referenceable = up;
        for (int i = scopes.size() - 1; i >= 0
                && referenceable.kind == CLASSSCOPE;i--,dist++) {
            referenceable = (scopes.get(i));
        }

        cur.cook(referenceable, dist, code_compiler);
        cur.dump(); // debug
        cur = up;
    }

    public void parse(PythonTree node) throws Exception {
        try {
            visit(node);
        } catch (Throwable t) {
            throw ParserFacade.fixParseError(null, t, code_compiler.getFilename());
        }
    }

    @Override
    public Object visitInteractive(Interactive node) throws Exception {
        beginScope("<single-top>", TOPSCOPE, node, null);
        suite(node.getInternalBody());
        endScope();
        return null;
    }

    @Override
    public Object visitModule(org.python.antlr.ast.Module node)
            throws Exception {
        beginScope("<file-top>", TOPSCOPE, node, null);
        suite(node.getInternalBody());
        endScope();
        return null;
    }

    @Override
    public Object visitExpression(Expression node) throws Exception {
        beginScope("<eval-top>", TOPSCOPE, node, null);
        visit(new Return(node,node.getInternalBody()));
        endScope();
        return null;
    }

    private void def(String name) {
        cur.addBound(name);
    }

    @Override
    public Object visitAsyncFunctionDef(AsyncFunctionDef node) throws Exception {
        String name = node.getInternalName();
        arguments args = node.getInternalArgs();
        List<expr> decs = node.getInternalDecorator_list();
        List<stmt> body = node.getInternalBody();
        return compileFunction(name, args, decs, body, node);
    }

    @Override
    public Object visitFunctionDef(FunctionDef node) throws Exception {
        String name = node.getInternalName();
        arguments args = node.getInternalArgs();
        List<expr> decs = node.getInternalDecorator_list();
        List<stmt> body = node.getInternalBody();
        return compileFunction(name, args, decs, body, node);
    }

    private Object compileFunction(String name, arguments args, List<expr> decs, List<stmt> body, stmt node) throws Exception {
        def(name);
        ArgListCompiler ac = new ArgListCompiler();
        ac.visitArgs(args);

        List<expr> defaults = ac.getDefaults();
        for (int i = 0; i < defaults.size(); i++) {
            visit(defaults.get(i));
        }

        for (int i = decs.size() - 1; i >= 0; i--) {
            visit(decs.get(i));
        }

        beginScope(name, FUNCSCOPE, node, ac);
        cur.async = node instanceof AsyncFunctionDef;
        int n = ac.names.size();
        for (int i = 0; i < n; i++) {
            cur.addParam(ac.names.get(i));
        }
        for (int i = 0; i < ac.init_code.size(); i++) {
            visit(ac.init_code.get(i));
        }
        cur.markFromParam();
        suite(body);
        endScope();
        return null;
    }

    @Override
    public Object visitLambda(Lambda node) throws Exception {
        ArgListCompiler ac = new ArgListCompiler();
        ac.visitArgs(node.getInternalArgs());

        List<? extends PythonTree> defaults = ac.getDefaults();
        for (int i = 0; i < defaults.size(); i++) {
            visit(defaults.get(i));
        }

        beginScope("<lambda>", FUNCSCOPE, node, ac);
        for (Object o : ac.names) {
            cur.addParam((String) o);
        }
        for (Object o : ac.init_code) {
            visit((stmt) o);
        }
        cur.markFromParam();
        visit(node.getInternalBody());
        endScope();
        return null;
    }

    public void suite(List<stmt> stmts) throws Exception {
        for (int i = 0; i < stmts.size(); i++)
            visit(stmts.get(i));
    }

    @Override
    public Object visitImport(Import node) throws Exception {
        for (int i = 0; i < node.getInternalNames().size(); i++) {
            if (node.getInternalNames().get(i).getInternalAsname() != null) {
                cur.addBound(node.getInternalNames().get(i).getInternalAsname());
            } else {
                String name = node.getInternalNames().get(i).getInternalName();
                if (name.indexOf('.') > 0) {
                    name = name.substring(0, name.indexOf('.'));
                }
                cur.addBound(name);
            }
        }
        return null;
    }

    @Override
    public Object visitImportFrom(ImportFrom node) throws Exception {
        Future.checkFromFuture(node); // future stmt support
        int n = node.getInternalNames().size();
        if (n == 0) {
            cur.from_import_star = true;
            return null;
        }
        for (int i = 0; i < n; i++) {
            if (node.getInternalNames().get(i).getInternalAsname() != null) {
                cur.addBound(node.getInternalNames().get(i).getInternalAsname());
            } else {
                cur.addBound(node.getInternalNames().get(i).getInternalName());
            }
        }
        return null;
    }

    @Override
    public Object visitGlobal(Global node) throws Exception {
        int n = node.getInternalNames().size();
        for (int i = 0; i < n; i++) {
            String name = node.getInternalNames().get(i);
            int prev = cur.addGlobal(name);
            if (prev >= 0) {
                if ((prev & FROM_PARAM) != 0) {
                    code_compiler.error("name '" + name
                            + "' is local and global", true, node);
                }
                if ((prev & GLOBAL) != 0) {
                    continue;
                }
                String what;
                if ((prev & BOUND) != 0) {
                    what = "assignment";
                } else {
                    what = "use";
                }
                code_compiler.error("name '" + name
                        + "' declared global after " + what, false, node);
            }
        }
        return null;
    }

    @Override
    public Object visitExec(Exec node) throws Exception {
        cur.exec = true;
        if (node.getInternalGlobals() == null && node.getInternalLocals() == null) {
            cur.unqual_exec = true;
        }
        traverse(node);
        return null;
    }

    @Override
    public Object visitClassDef(ClassDef node) throws Exception {
        String outer = "<outer" + node.getInternalName() + ">";
        String clsname = node.getInternalName();
        String inner = "<inner" + clsname +">";
        Name innerName = new Name(node.getToken(), inner, expr_contextType.Store);
        def(outer);
        String vararg = "__(args)__";
        String kwarg = "__(kw)__";
        List<stmt> bod = new ArrayList<>();
        bod.add(node);
        Assign assign = new Assign(node.getToken(), Arrays.<expr>asList(new Name(node.getToken(), "__class__", expr_contextType.Store)), innerName);
        bod.add(assign);
        Return _ret = new Return(node.getToken(), innerName);
        bod.add(_ret);

        arguments args = new arguments(node, new ArrayList<expr>(),
                vararg, new ArrayList<String>(), new ArrayList<expr>(), kwarg, new ArrayList<expr>());
        FunctionDef funcdef = new FunctionDef(node.getToken(), outer, args, bod, new ArrayList<expr>());

        ArgListCompiler ac = new ArgListCompiler();
        ac.visitArgs(args);

        int n = node.getInternalBases().size();
        for (int i = 0; i < n; i++) {
            visit(node.getInternalBases().get(i));
        }
        beginScope(outer, FUNCSCOPE, funcdef, ac);
        cur.addParam(vararg);
        cur.addParam(kwarg);
        cur.markFromParam();

        List<expr> decs = node.getInternalDecorator_list();
        for (int i = decs.size() - 1; i >= 0; i--) {
            visit(decs.get(i));
        }
        def(inner);

        beginScope(inner, CLASSSCOPE, node, null);
        cur.needs_class_closure = true;
        suite(node.getInternalBody());
        endScope();

        def("__class__");
        visit(_ret);
        endScope();
        def(clsname);

        return null;
    }

    @Override
    public Object visitName(Name node) throws Exception {
        String name = node.getInternalId();
        if (node.getInternalCtx() == expr_contextType.Load && name.equals("super")) {
            cur.addUsed("__class__");
        }
        if (node.getInternalCtx() != expr_contextType.Load) {
            if (name.equals("__debug__")) {
                code_compiler.error("can not assign to __debug__", true, node);
            }
            cur.addBound(name);
        } else {
            cur.addUsed(name);
        }
        return null;
    }

    @Override
    public Object visitListComp(ListComp node) throws Exception {
        return visitInternalGenerators(node, node.getInternalElt(), node.getInternalGenerators());
    }

    @Override
    public Object visitDictComp(DictComp node) throws Exception {
        java.util.List<expr> kv = Arrays.asList(node.getInternalKey(), node.getInternalValue());
        return visitInternalGenerators(
                node, new Tuple(node, kv, expr_contextType.UNDEFINED), node.getInternalGenerators());
    }

    @Override
    public Object visitSetComp(SetComp node) throws Exception {
        return visitInternalGenerators(node, node.getInternalElt(), node.getInternalGenerators());
    }

    @Override
    public Object visitAwait(Await node) throws Exception {
        traverse(node);
        return null;
    }

    @Override
    public Object visitYieldFrom(YieldFrom node) throws Exception {
        traverse(node);
        return null;
    }

    @Override
    public Object visitYield(Yield node) throws Exception {
        cur.defineAsGenerator(node);
        cur.yield_count++;
        traverse(node);
        return null;
    }

    @Override
    public Object visitReturn(Return node) throws Exception {
        if (node.getInternalValue() != null) {
            cur.noteReturnValue(node);
        }
        traverse(node);
        return null;
    }

    private Object visitInternalGenerators(expr node, expr elt, java.util.List<comprehension> generators)
            throws Exception {
        // The first iterator is evaluated in the outer scope
        if (generators != null && generators.size() > 0) {
            visit(generators.get(0).getInternalIter());
        }
        String bound_exp = "_(x)";
        String tmp = "_(" + node.getLine() + "_" + node.getCharPositionInLine()
                + ")";
        def(tmp);
        ArgListCompiler ac = new ArgListCompiler();
        List<expr> args = new ArrayList<expr>();
        args.add(new Name(node.getToken(), bound_exp, expr_contextType.Param));
        String vararg = null;
        List<String> kwonlyargs = new ArrayList<>();
        List<expr> kw_defaults = new ArrayList<>();
        String kwarg = null;
        List<expr> defaults = new ArrayList<>();
        ac.visitArgs(new arguments(node, args, vararg, kwonlyargs, kw_defaults, kwarg, defaults));
        beginScope(tmp, FUNCSCOPE, node, ac);
        cur.addParam(bound_exp);
        cur.markFromParam();

        cur.defineAsGenerator(node);
        cur.yield_count++;
        // The reset of the iterators are evaluated in the inner scope
        if (elt != null) {
            visit(elt);
        }
        if (generators != null) {
            for (int i = 0; i < generators.size(); i++) {
                if (generators.get(i) != null) {
                    if (i == 0) {
                        visit(generators.get(i).getInternalTarget());
                        if (generators.get(i).getInternalIfs() != null) {
                            for (expr cond : generators.get(i).getInternalIfs()) {
                                if (cond != null) {
                                    visit(cond);
                                }
                            }
                        }
                    } else {
                        visit(generators.get(i));
                    }
                }
            }
        }

        endScope();
        return null;
    }

    @Override
    public Object visitGeneratorExp(GeneratorExp node) throws Exception {
        return visitInternalGenerators(node, node.getInternalElt(), node.getInternalGenerators());
    }

    @Override
    public Object visitWith(With node) throws Exception {
        cur.max_with_count++;
        traverse(node);

        return null;
    }

    public Object visitExceptHandler(ExceptHandler node) throws Exception {
        traverse(node);
        if (node.getInternalName() != null) {
            def(node.getInternalName());
        }
        return null;
    }

}
