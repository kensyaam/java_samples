package com.example.test;

/**
 * SQL文字列連結のテストケース
 */
public class SqlConcatenationTest {

    /**
     * シンプルな文字列連結
     */
    public void testSimpleConcatenation() {
        String sql = "SELECT * " + "FROM user " + "WHERE id = :id";
        System.out.println(sql);
    }

    /**
     * 複数行にわたる文字列連結
     */
    public void testMultiLineConcatenation() {
        String sql = "SELECT * " +
                "FROM user /* ユーザ */ " +
                "WHERE id = :id /* 主キー */ ";
        System.out.println(sql);
    }

    /**
     * より複雑な連結パターン
     */
    public void testComplexConcatenation() {
        String sql = "SELECT u.id, u.name, u.email " +
                "FROM user u " +
                "INNER JOIN department d ON u.dept_id = d.id " +
                "WHERE u.status = 'ACTIVE' " +
                "ORDER BY u.name";
        System.out.println(sql);
    }

    /**
     * UPDATE文の連結
     */
    public void testUpdateConcatenation() {
        String sql = "UPDATE user " +
                "SET name = :name, " +
                "    email = :email " +
                "WHERE id = :id";
        System.out.println(sql);
    }

    /**
     * INSERT文の連結
     */
    public void testInsertConcatenation() {
        String sql = "INSERT INTO user " +
                "(id, name, email, created_at) " +
                "VALUES " +
                "(:id, :name, :email, NOW())";
        System.out.println(sql);
    }

    /**
     * DELETE文の連結
     */
    public void testDeleteConcatenation() {
        String sql = "DELETE FROM user " +
                "WHERE id = :id " +
                "AND status = 'INACTIVE'";
        System.out.println(sql);
    }

    /**
     * 変数への代入後に使用
     */
    public void testVariableAssignment() {
        String selectPart = "SELECT * ";
        String fromPart = "FROM user ";
        String wherePart = "WHERE id = :id";

        // この連結は検出されない(変数参照は未対応)
        String sql = selectPart + fromPart + wherePart;
        System.out.println(sql);
    }

    /**
     * 直接的な連結のみ(こちらは検出される)
     */
    public void testDirectConcatenation() {
        String sql;
        sql = "SELECT * " + "FROM user " + "WHERE id = :id";
        System.out.println(sql);
    }

    /**
     * PL/SQL呼び出しの連結
     */
    public void testPlSqlConcatenation() {
        String sql = "CALL get_user_info(" +
                ":userId, " +
                ":userName" +
                ")";
        System.out.println(sql);
    }

    /**
     * 三項演算子を使った動的SQL生成 (ユーザー要求例)
     * 期待: SELECT * FROM user ${UNRESOLVED} ORDER BY id
     */
    public void testConditionalConcatenation() {
        boolean isAdmin = true;
        String sql = "SELECT * FROM user " +
                (isAdmin ? "WHERE admin_flg = 1 " : "") +
                "ORDER BY id";
        System.out.println(sql);
    }

    /**
     * 三項演算子を使った動的SQL生成 (複雑なケース)
     * 期待: SELECT ${UNRESOLVED} FROM user WHERE ${UNRESOLVED}
     */
    public void testComplexConditionalConcatenation() {
        boolean includeAll = false;
        String condition = "status = 'ACTIVE'";
        String sql = "SELECT " +
                (includeAll ? "*" : "id, name") +
                " FROM user WHERE " +
                condition;
        System.out.println(sql);
    }

    /**
     * 変数参照を含む動的SQL生成
     * 期待: SELECT * FROM ${UNRESOLVED} WHERE id = 1
     */
    public void testVariableReferenceConcatenation() {
        String tableName = "user";
        String sql = "SELECT * FROM " + tableName + " WHERE id = 1";
        System.out.println(sql);
    }

    /**
     * メソッド呼び出しを含む動的SQL生成
     * 期待: SELECT * FROM ${UNRESOLVED} WHERE id = 1
     */
    public void testMethodCallConcatenation() {
        String sql = "SELECT * FROM " + getTableName() + " WHERE id = 1";
        System.out.println(sql);
    }

    private String getTableName() {
        return "user";
    }

    /**
     * 通常の文字列(SQL以外)
     */
    public void testNonSqlConcatenation() {
        String message = "Hello, " + "World! " + "This is a test.";
        System.out.println(message);
    }
}
