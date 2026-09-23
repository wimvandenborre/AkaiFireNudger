package com.akai.fire.sequence;

import java.util.*;

/** Tiny test-only JSON reader: fixtures stay authoritative; no runtime JSON dependency. */
final class FixtureJson {
    private final String text; private int pos;
    FixtureJson(String text){this.text=text;}
    Object read(){Object value=value();space();if(pos!=text.length())throw new IllegalArgumentException("Trailing JSON");return value;}
    private void space(){while(pos<text.length()&&Character.isWhitespace(text.charAt(pos)))pos++;}
    private char take(){return text.charAt(pos++);}
    private void expect(char c){space();if(take()!=c)throw new IllegalArgumentException("Expected "+c+" at "+pos);}
    private Object value(){space();char c=text.charAt(pos);
        if(c=='{'){take();Map<String,Object> m=new LinkedHashMap<>();space();if(text.charAt(pos)=='}'){take();return m;}do{String key=string();expect(':');m.put(key,value());space();c=take();}while(c==',');if(c!='}')throw new IllegalArgumentException();return m;}
        if(c=='['){take();List<Object> a=new ArrayList<>();space();if(text.charAt(pos)==']'){take();return a;}do{a.add(value());space();c=take();}while(c==',');if(c!=']')throw new IllegalArgumentException();return a;}
        if(c=='"')return string();
        for(String literal:List.of("true","false","null"))if(text.startsWith(literal,pos)){pos+=literal.length();return literal.equals("null")?null:Boolean.valueOf(literal);}
        int start=pos;while(pos<text.length()&&"-+0123456789.eE".indexOf(text.charAt(pos))>=0)pos++;
        return Double.valueOf(text.substring(start,pos));
    }
    private String string(){expect('"');StringBuilder s=new StringBuilder();for(;;){char c=take();if(c=='"')return s.toString();if(c=='\\'){c=take();switch(c){case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;case 'b':c='\b';break;case 'f':c='\f';break;case 'u':c=(char)Integer.parseInt(text.substring(pos,pos+4),16);pos+=4;break;default:break;}}s.append(c);}}
}
