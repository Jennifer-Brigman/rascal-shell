package org.rascalmpl.shell;
/*******************************************************************************
 * Copyright (c) 2009-2011 CWI
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:

 *   * Jurgen J. Vinju - Jurgen.Vinju@cwi.nl - CWI
 *   * Tijs van der Storm - Tijs.van.der.Storm@cwi.nl
 *   * Paul Klint - Paul.Klint@cwi.nl - CWI
 *   * Arnold Lankamp - Arnold.Lankamp@cwi.nl
*******************************************************************************/


import static org.rascalmpl.interpreter.utils.ReadEvalPrintDialogMessages.parseErrorMessage;
import static org.rascalmpl.interpreter.utils.ReadEvalPrintDialogMessages.staticErrorMessage;
import static org.rascalmpl.interpreter.utils.ReadEvalPrintDialogMessages.throwMessage;
import static org.rascalmpl.interpreter.utils.ReadEvalPrintDialogMessages.throwableMessage;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.util.List;

import jline.ConsoleReader;

import org.eclipse.imp.pdb.facts.IConstructor;
import org.eclipse.imp.pdb.facts.IListWriter;
import org.eclipse.imp.pdb.facts.ISourceLocation;
import org.eclipse.imp.pdb.facts.IString;
import org.eclipse.imp.pdb.facts.IValue;
import org.eclipse.imp.pdb.facts.IValueFactory;
import org.eclipse.imp.pdb.facts.exceptions.FactTypeUseException;
import org.eclipse.imp.pdb.facts.type.Type;
import org.eclipse.imp.pdb.facts.type.TypeFactory;
import org.rascalmpl.interpreter.Evaluator;
import org.rascalmpl.interpreter.asserts.ImplementationError;
import org.rascalmpl.interpreter.control_exceptions.QuitException;
import org.rascalmpl.interpreter.control_exceptions.Throw;
import org.rascalmpl.interpreter.env.GlobalEnvironment;
import org.rascalmpl.interpreter.env.ModuleEnvironment;
import org.rascalmpl.interpreter.load.RascalURIResolver;
import org.rascalmpl.interpreter.result.Result;
import org.rascalmpl.interpreter.staticErrors.StaticError;
import org.rascalmpl.interpreter.utils.ReadEvalPrintDialogMessages;
import org.rascalmpl.parser.gtd.exception.ParseError;
import org.rascalmpl.uri.URIUtil;
import org.rascalmpl.values.ValueFactoryFactory;
import org.rascalmpl.values.uptr.Factory;
import org.rascalmpl.values.uptr.TreeAdapter;

public class RascalShell {
	private final static int LINE_LIMIT = 200;
	
	private final ConsoleReader console;
	private final Evaluator evaluator;
	private volatile boolean running;
	
	
	// TODO: cleanup these constructors.
	public RascalShell() throws IOException {
		console = new ConsoleReader();
		GlobalEnvironment heap = new GlobalEnvironment();
		ModuleEnvironment root = heap.addModule(new ModuleEnvironment(ModuleEnvironment.SHELL_MODULE, heap));
		PrintWriter stderr = new PrintWriter(System.err);
		PrintWriter stdout = new PrintWriter(System.out);
		evaluator = new Evaluator(ValueFactoryFactory.getValueFactory(), stderr, stdout, root, heap);
		importPrelude();
		running = true;
	}
	
	public RascalShell(InputStream stdin, PrintWriter stderr, PrintWriter stdout) throws IOException {
		console = new ConsoleReader(stdin, new PrintWriter(stdout));
		GlobalEnvironment heap = new GlobalEnvironment();
		ModuleEnvironment root = heap.addModule(new ModuleEnvironment(ModuleEnvironment.SHELL_MODULE, heap));
		evaluator = new Evaluator(ValueFactoryFactory.getValueFactory(), stderr, stdout, root, heap);
		importPrelude();
		running = true;
	}
	
	public RascalShell(InputStream stdin, PrintWriter stderr, PrintWriter stdout, List<ClassLoader> classLoaders, RascalURIResolver uriResolver) throws IOException {
		console = new ConsoleReader(stdin, new PrintWriter(stdout));
		GlobalEnvironment heap = new GlobalEnvironment();
		ModuleEnvironment root = heap.addModule(new ModuleEnvironment(ModuleEnvironment.SHELL_MODULE, heap));
		evaluator = new Evaluator(ValueFactoryFactory.getValueFactory(), stderr, stdout, root, heap, classLoaders, uriResolver);
		importPrelude();
		running = true;
	}
	
	private void importPrelude(){
		//synchronized(evaluator){
		//	evaluator.doImport(null, "Prelude");
		//}
	}
	
	public void run() throws IOException {
		StringBuilder input = new StringBuilder();
		String line;
		
		next:while (running) {
			try {
				input.delete(0, input.length());
				String prompt = ReadEvalPrintDialogMessages.PROMPT;

				do {
					line = console.readLine(prompt);
					
					if (line == null) {
						break next; // EOF
					}
					
					if (line.trim().length() == 0) {
						console.printString("cancelled\n");
						continue next;
					}
					
					input.append((input.length() > 0 ? "\n" : "") + line);
					prompt = ReadEvalPrintDialogMessages.CONTINUE_PROMPT;
				} while (!completeStatement(input.toString()));

				String output = handleInput(input.toString());
				console.printString(output);
				console.printNewline();
			}
			catch (ParseError pe) {
				console.printString(parseErrorMessage(input.toString(), "prompt", pe));
				console.printNewline();
			}
			catch (StaticError e) {
				console.printString(staticErrorMessage(e));
				console.printNewline();
			}
			catch (Throw e) {
				console.printString(throwMessage(e));
				console.printNewline();
			}
			catch (QuitException q) {
				break next;
			}
			catch (Throwable e) {
				console.printString(throwableMessage(e, evaluator.getStackTrace()));
				console.printNewline();
			}
		}
	}
	
	public synchronized void stop(){
		running = false;
		evaluator.interrupt();
	}
	
	public Evaluator getEvaluator() {
		return evaluator;
	}
	
	private String handleInput(String statement){
		Result<IValue> value = evaluator.eval(null, statement, URIUtil.rootScheme("prompt"));

		if (value.getValue() == null) {
			return "ok";
		}

		IValue v = value.getValue();
		Type type = value.getType();

		if (type.isAbstractDataType() && type.isSubtypeOf(Factory.Tree)) {
			return "`" + TreeAdapter.yield((IConstructor) v) + "`\n" + value.toString(LINE_LIMIT);
		}

		return ((v != null) ? value.toString(LINE_LIMIT) : null);
	}

	private boolean completeStatement(String command) throws FactTypeUseException {
		try {
			evaluator.parseCommand(null, command, URIUtil.rootScheme("prompt"));
		}
		catch (ParseError pe) {
			String[] commandLines = command.split("\n");
			int lastLine = commandLines.length;
			int lastColumn = commandLines[lastLine - 1].length();
			
			if (pe.getEndLine() + 1 == lastLine && lastColumn <= pe.getEndColumn()) { 
				return false;
			}
		}
		
		return true;
	}
	
	public static void main(String[] args) throws IOException {
		if (args.length == 0) {
			// interactive mode
			try {
				new RascalShell().run();
				System.exit(0);
			} catch (IOException e) {
				System.err.println("unexpected error: " + e.getMessage());
				System.exit(1);
			} 
		}
		if (args[0].equals("-latex")) {
			toLatex(args[1]);
		}
		else {
			runModule(args);
		}
	}

	private static void runModule(String args[]) {
		String module = args[0];
		if (module.endsWith(".rsc")) {
			module = module.substring(0, module.length() - 4);
		}
		module = module.replaceAll("/", "::");
		Evaluator evaluator = getDefaultEvaluator();
		IValueFactory vf = ValueFactoryFactory.getValueFactory();
		TypeFactory tf = TypeFactory.getInstance();
		IListWriter w = vf.listWriter(tf.stringType());
		for (int i = 1; i < args.length; i++) {
			w.append(vf.string(args[i]));
		}
		try {
			evaluator.doImport(null, module);
			IValue v = evaluator.call(null, "main", w.done());
			if (v != null) {
				System.out.println(v.toString());
			}
			return;
		}
		catch (ParseError pe) {
			URI uri = pe.getLocation();
			System.err.println("Parse error in " + uri + " from <" + (pe.getBeginLine() + 1)+","+pe.getBeginColumn()+"> to <"+(pe.getEndLine() + 1)+","+pe.getEndColumn()+">");
		}
		catch (StaticError e) {
			System.err.println("Static Error: " + e.getMessage());
			e.printStackTrace(); // for debugging only
		}
		catch (Throw e) {
			System.err.println("Uncaught Rascal Exception: " + e.getMessage());
			System.err.println(e.getTrace().toLinkedString());
		}
		catch (ImplementationError e) {
			e.printStackTrace();
			System.err.println("ImplementationError: " + e.getMessage());
		}
		catch (Throwable e) {
			System.err.println("Unexpected exception (generic Throwable): " + e.getMessage());
			System.err.println(evaluator.getStackTrace());
		}
	}

	private static Evaluator getDefaultEvaluator() {
		GlobalEnvironment heap = new GlobalEnvironment();
		ModuleEnvironment root = heap.addModule(new ModuleEnvironment(ModuleEnvironment.SHELL_MODULE, heap));
		PrintWriter stderr = new PrintWriter(System.err);
		PrintWriter stdout = new PrintWriter(System.out);
		IValueFactory vf = ValueFactoryFactory.getValueFactory();
		Evaluator evaluator = new Evaluator(vf, stderr, stdout, root, heap);
		return evaluator;
	}
	
	private static void toLatex(String fileName) throws IOException {
		Evaluator evaluator = getDefaultEvaluator();
		evaluator.doImport(null, "lang::rascal::doc::ToLatex");
		File file = new File(fileName);
		String name = file.getName();
		int pos = name.lastIndexOf('.');
		if (pos < 0) {
			System.err.println("No extension in file " + fileName);
			System.exit(1);
		}
		String ext = name.substring(pos + 1);
		
		if (ext.equals("ltx")) {
			System.err.println("Using output extension ltx, but source file has the same extension");
			System.exit(1);
		}
		final String destExt = ".ltx";
		File dest = new File(file.getParent(), name.substring(0, pos) + destExt); 
		
		System.err.println("Formatting Rascal snippets in " + file + "; outputting to " + dest + "...");
		System.err.flush();
		IValueFactory vf = ValueFactoryFactory.getValueFactory();
		ISourceLocation loc = vf.sourceLocation(file.getAbsolutePath());
		IString str = (IString) evaluator.call(null, "rascalDoc2Latex", loc);
		FileWriter writer = new FileWriter(dest);
		writer.write(str.getValue());
		writer.close();
		System.err.println("Done.");
	}
}