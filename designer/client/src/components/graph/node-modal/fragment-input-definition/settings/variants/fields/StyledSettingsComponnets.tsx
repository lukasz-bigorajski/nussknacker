import React from "react";
import { Switch, Typography, styled, FormLabel, css } from "@mui/material";
import { NodeRow } from "../../../../NodeDetailsContent/NodeStyled";
import InfoIcon from "@mui/icons-material/Info";
import { StyledNodeTip } from "../../../../FieldLabel";

export const SettingsWrapper = styled("div")`
    padding: 10px;
    border: 1px solid #ffffff1f;
    width: 100%;
    display: block;
    margin-bottom: 20px;
`;

export const SettingLabelStyled = styled(FormLabel)(
    ({ theme }) => css`
        font-family: Open Sans;
        color: ${theme.custom.colors.baseColor};
        font-size: 12px;
        font-weight: 400;
        line-height: 16px;
        letter-spacing: -0.01em;
        text-align: left;
        vertical-align: top;
        margin-top: 9px;
        display: flex;
        flex-basis: 30%;
    `,
);

export const ListItemContainer = styled("div")`
    width: 100%;
    display: flex;
    justify-content: flex-end;
`;

export const ListItemWrapper = styled("div")`
    width: 70%;
    display: flex;
    justify-content: flex-start;
    max-height: 100px;
    flex-wrap: wrap;
    overflow: auto;
    margin-top: 10px;
    ::-webkit-scrollbar-track {
        width: 15px;
        height: 100px;
        background: rgba(51, 51, 51, 1);
    }
    ::-webkit-scrollbar-thumb {
        background: rgba(173, 173, 173, 1);
        background-clip: content-box;
        border: 3.5px solid transparent;
        border-radius: 100px;
        height: 60px;
    }
    ::-webkit-scrollbar {
        width: 15px;
        height: 100px;
    }
`;

export const SettingRow = styled(NodeRow)``;

export const CustomSwitch = styled(Switch)`
    input[type="checkbox"] {
        all: initial !important;
    }
    input[type="checkbox"]:after {
        all: initial !important;
    }
`;

export const StyledFormControlLabel = styled(Typography)`
    font-family: Open Sans;
    font-size: 12px;
    font-weight: 400;
    line-height: 18px;
    letter-spacing: 0.15000000596046448px;
    text-align: left;
`;

export const fieldLabel = ({ label, required = false, hintText }: { label: string; required?: boolean; hintText?: string }) => (
    <SettingLabelStyled required={required}>
        {label}
        {hintText && <StyledNodeTip title={hintText} icon={<InfoIcon />} />}
    </SettingLabelStyled>
);
