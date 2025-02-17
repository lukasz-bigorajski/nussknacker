export enum ExpressionLang {
    SQL = "sql",
    SpEL = "spel",
    SpELTemplate = "spelTemplate",
    String = "string",
    JSON = "json",
}

export type ExpressionObj = {
    expression: string;
    language: ExpressionLang | string;
};

export enum EditorMode {
    SpEL = "spel",
    SpELTemplate = "spelTemplate",
    SQL = "sql",
}
