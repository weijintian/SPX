package com.spx.chat;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

/** Deterministic auth form-state checks. Never sends email or creates an account. */
public final class AuthenticationFlowInstrumentation extends Instrumentation {
    private int checks;
    private long now = 10_000;
    private Object state;
    private Class<?> stateClass;
    private void check(boolean value, String name) { if (!value) throw new AssertionError(name); checks++; }
    private java.lang.reflect.Method method(String name, Class<?>... types) throws Exception {
        java.lang.reflect.Method value = stateClass.getDeclaredMethod(name, types); value.setAccessible(true); return value;
    }
    private Object call(String name, Class<?>[] types, Object... args) throws Exception { return method(name, types).invoke(state, args); }
    private Object callStatic(String name, Class<?>[] types, Object... args) throws Exception { return method(name, types).invoke(null, args); }
    private void checkCall(boolean expected, String name, Class<?>[] types, Object... args) throws Exception {
        check(Boolean.valueOf(expected).equals(call(name, types, args)), name);
    }
    private String draft(String key) throws Exception { return (String)call("draft", new Class<?>[]{String.class}, key); }

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        super.onStart(); Bundle result = new Bundle();
        try {
            stateClass = getTargetContext().getClassLoader().loadClass("com.spx.chat.AuthFormState");
            java.lang.reflect.Constructor<?> constructor = stateClass.getDeclaredConstructor(java.util.function.LongSupplier.class);
            constructor.setAccessible(true); state = constructor.newInstance((java.util.function.LongSupplier)() -> now);
            int loginPage = (Integer)call("nextPage", new Class<?>[]{});
            call("draft", new Class<?>[]{String.class,String.class}, "login.account", " person@example.com ");
            call("draft", new Class<?>[]{String.class,String.class}, "login.password", "memory-only");
            check(draft("login.account").equals(" person@example.com "), "login field draft survives page recreation");
            check(draft("login.password").equals("memory-only"), "password remains in transient memory only");
            int registerPage = (Integer)call("nextPage", new Class<?>[]{});
            check(registerPage != loginPage && draft("login.account").equals(" person@example.com "), "tab switch has distinct page generation and preserves draft");

            call("draft", new Class<?>[]{String.class,String.class}, "register.email", " Person@Example.com ");
            check(callStatic("address",new Class<?>[]{String.class},draft("register.email")).equals("person@example.com"), "email cooldown identity is normalized");
            check(!(Boolean)callStatic("validEmail",new Class<?>[]{String.class},""), "empty email is rejected before send");
            check((Boolean)callStatic("validEmail",new Class<?>[]{String.class},"person@example.com"), "valid email is accepted");
            check(!(Boolean)callStatic("validEmail",new Class<?>[]{String.class},"person@localhost"), "malformed email is rejected");
            Class<?>[] pair={String.class,String.class};
            checkCall(true,"beginCode",pair,"Person@Example.com","register");
            checkCall(true,"hasPendingCodeRequest",new Class<?>[]{});
            checkCall(false,"beginSubmit",new Class<?>[]{});
            checkCall(false,"beginCode",pair," person@example.com ","register");
            checkCall(true,"sending",pair,"PERSON@example.com","register");
            call("finishCode",new Class<?>[]{String.class,String.class,boolean.class},"person@example.com","register",false);
            checkCall(false,"hasPendingCodeRequest",new Class<?>[]{});
            check(((Integer)call("remaining",pair,"person@example.com","register"))==0,"failed send does not start cooldown");
            checkCall(true,"beginCode",pair,"person@example.com","register");
            call("finishCode",new Class<?>[]{String.class,String.class,boolean.class},"person@example.com","register",true);
            check(((Integer)call("remaining",pair,"PERSON@example.com","register"))==60,"successful send starts cooldown");
            checkCall(false,"beginCode",pair,"person@example.com","register");
            check(((Integer)call("remaining",pair,"other@example.com","register"))==0,"different address has an independent cooldown");
            check(((Integer)call("remaining",pair,"person@example.com","password_reset"))==0,"different purpose has an independent cooldown");
            now += 59_100;
            check(((Integer)call("remaining",pair,"person@example.com","register"))==1,"countdown uses monotonic elapsed time");
            now += 900;
            check(((Integer)call("remaining",pair,"person@example.com","register"))==0,"send unlocks at deadline");

            check((Boolean)callStatic("validCode",new Class<?>[]{String.class},"012345"),"six ASCII digits are accepted");
            check(!(Boolean)callStatic("validCode",new Class<?>[]{String.class},"12345"),"short code is rejected");
            check(!(Boolean)callStatic("validCode",new Class<?>[]{String.class},"1234567"),"long code is rejected");
            check(!(Boolean)callStatic("validCode",new Class<?>[]{String.class},"１２３４５６"),"non-ASCII digits are rejected");
            checkCall(false,"submitting",new Class<?>[]{});
            checkCall(true,"beginSubmit",new Class<?>[]{});
            checkCall(false,"beginSubmit",new Class<?>[]{});
            checkCall(true,"submitting",new Class<?>[]{});
            call("finishSubmit",new Class<?>[]{});
            checkCall(true,"beginSubmit",new Class<?>[]{});
            call("finishSubmit",new Class<?>[]{});
            call("clearDrafts",new Class<?>[]{});
            check(draft("login.password").isEmpty() && draft("register.email").isEmpty(), "successful auth clears transient form values");

            result.putString("stream", "SUCCESS " + checks + " auth form-state and cooldown checks; no email sent or account created\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL " + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
