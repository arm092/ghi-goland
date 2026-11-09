package am.ghi.ide;

import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/** Contextual modifiers must not consume an existing field name and its type. */
final class GhiModifiers {
    private GhiModifiers() {}
    static boolean at(int index,IntFunction<String> word,IntPredicate identifier,IntPredicate newlineAfter){
        String current=word.apply(index),next=word.apply(index+1);
        boolean abstractWord=current.equals("abstract");
        if(!abstractWord&&!current.equals("static")||newlineAfter.test(index))return false;
        if(next.equals("func")&&word.apply(index+2).equals("("))return false;
        // A complete named type after the identifier wins over modifier parsing,
        // including contextual type names such as constructor or public.
        if(name(next)){
            int end=index+2;
            while(word.apply(end).equals(".")&&name(word.apply(end+1)))end+=2;
            if(word.apply(end).equals("[")){
                int depth=0;
                do{
                    String token=word.apply(end++);
                    if(token.isEmpty())return false;
                    if(token.equals("["))depth++;
                    if(token.equals("]"))depth--;
                }while(depth>0);
            }
            if(newlineAfter.test(end-1)||Set.of("",";","}","=").contains(word.apply(end)))return false;
        }
        if(abstractWord&&next.equals("class")||next.equals("func")&&identifier.test(index+2)
            ||next.equals("constructor")&&word.apply(index+2).equals("(")
            ||Set.of("public","private","protected","override").contains(next))return true;
        if((next.equals("abstract")||next.equals("static"))&&at(index+1,word,identifier,newlineAfter))return true;
        String after=word.apply(index+2);
        return !abstractWord&&identifier.test(index+1)&&!newlineAfter.test(index+1)
            &&(identifier.test(index+2)||Set.of("?","*","[","map","chan","func","interface","struct","<","constructor","public","private","protected","override").contains(after));
    }
    private static boolean name(String word){return !word.isEmpty()&&(Character.isLetter(word.codePointAt(0))||word.charAt(0)=='_');}
}
